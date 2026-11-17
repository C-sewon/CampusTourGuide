import asyncio
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Response
from fastapi.responses import JSONResponse
from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import config, lab_sync, models, processing, recommend, schemas
from .database import Base, engine, ensure_current_schema, get_db
from .seed import seed_if_empty


async def _lab_sync_loop():
    while True:
        await asyncio.to_thread(lab_sync.run_once)
        await asyncio.sleep(config.LAB_SYNC_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_current_schema()
    Base.metadata.create_all(engine)
    seed_if_empty(sample_readings=not config.LAB_SYNC_ENABLED)
    task = asyncio.create_task(_lab_sync_loop()) if config.LAB_SYNC_ENABLED else None
    yield
    if task is not None:
        task.cancel()


class Utf8JSONResponse(JSONResponse):
    """글자 인코딩(charset)을 응답 헤더에 밝히는 JSON 응답.

    헤더가 application/json 뿐이면 일부 브라우저(예: iPhone Safari)가 한글을 엉뚱한 인코딩으로 읽어
    깨져 보인다. 내용은 원래 UTF-8 이므로 헤더에 charset=utf-8 을 적어 준다.
    """

    media_type = "application/json; charset=utf-8"


app = FastAPI(
    title="Campus BLE Tour Guide API", lifespan=lifespan, default_response_class=Utf8JSONResponse
)


def _reading_out(r: models.Reading, now: int, suspect: bool) -> schemas.ReadingOut:
    age = processing.age_minutes(r.measured_at, now)
    return schemas.ReadingOut(
        sensor_id=r.sensor_id,
        measured_at=r.measured_at,
        temperature=r.temperature,
        humidity=r.humidity,
        age_minutes=age,
        is_stale=processing.is_stale(age, config.STALE_MINUTES),
        is_suspect=suspect,
    )


def _valid_clause():
    """오류값을 뺀 조건. processing.is_valid_reading 과 같은 기준.

    습도가 없는 값(NULL)은 온도 범위만 본다. SQL 에서 NULL 비교는 거짓이 되므로 NULL 을 따로 다룬다.
    """
    R = models.Reading
    return and_(
        R.temperature.between(*processing.TEMPERATURE_RANGE),
        or_(R.humidity.is_(None), R.humidity.between(*processing.HUMIDITY_RANGE)),
        or_(R.temperature != 0, R.humidity.is_(None), R.humidity != 0),
    )


def _is_fixed(db: Session, newest: models.Reading) -> bool:
    """최신 측정값과 온도, 습도가 같은 값이 이어진 구간이 충분히 길면 True.

    같은 값이 시작된 시점은 '마지막으로 다른 값이 측정된 시각' 이후로 본다.
    센서 오류값(0.0)은 구간 계산에서 제외한다.

    BLE 로 받은 값(소수점 둘째 자리까지)에만 적용한다. 연구실 API 값은 소수점이 버려진 정수 온도라서
    몇 분 동안 같은 값이 나오는 것이 정상이므로 고정값으로 보지 않는다.
    """
    R = models.Reading
    if newest.source != "ble" or newest.humidity is None:
        return False
    last_different = db.scalar(
        select(func.max(R.measured_at)).where(
            R.sensor_id == newest.sensor_id,
            R.measured_at <= newest.measured_at,
            R.source == "ble",
            or_(R.temperature != newest.temperature, R.humidity != newest.humidity),
            _valid_clause(),
        )
    )
    in_run = [
        R.sensor_id == newest.sensor_id,
        R.measured_at <= newest.measured_at,
        R.source == "ble",
        R.temperature == newest.temperature,
        R.humidity == newest.humidity,
        _valid_clause(),
    ]
    if last_different is not None:
        in_run.append(R.measured_at > last_different)

    count, first = db.execute(select(func.count(), func.min(R.measured_at)).where(*in_run)).one()
    return processing.is_fixed_run(
        count,
        newest.measured_at - first,
        config.FIXED_VALUE_MIN_COUNT,
        config.FIXED_VALUE_MINUTES,
    )


def _sensor_environment(
    db: Session, sensor_id: int | None
) -> tuple[schemas.ReadingOut | None, schemas.AverageOut | None]:
    """센서의 최신 측정값과 최근 평균. 값은 저장된 그대로 두고 표시(flag)만 한다.

    온도와 습도가 모두 0.0인 오류값은 저장은 하지만 최신값과 평균에서 제외한다.
    """
    if sensor_id is None:
        return None, None

    now = int(time.time())
    newest = db.scalars(
        select(models.Reading)
        .where(models.Reading.sensor_id == sensor_id, _valid_clause())
        .order_by(models.Reading.measured_at.desc(), models.Reading.id.desc())
        .limit(1)
    ).first()
    if newest is None:
        return None, None

    suspect = _is_fixed(db, newest)
    latest = _reading_out(newest, now, suspect)
    if suspect:  # 멈춘 센서 값으로는 평균을 내지 않는다
        return latest, None

    window = db.scalars(
        select(models.Reading).where(
            models.Reading.sensor_id == sensor_id,
            models.Reading.measured_at >= now - config.AVG_WINDOW_MINUTES * 60,
            _valid_clause(),
        )
    ).all()
    result = processing.average(window)
    average = None
    if result is not None:
        temperature, humidity, count = result
        average = schemas.AverageOut(
            temperature=temperature,
            humidity=humidity,
            count=count,
            window_minutes=config.AVG_WINDOW_MINUTES,
        )
    return latest, average


def _borrow_nearby_sensor(db: Session, spot: models.Spot, exclude_ids: set[int]):
    """장소에서 MAX_BORROW_METERS 안에 있는 센서 중, 오래되지 않은 정상 값을 가진 가장 가까운 센서.

    (센서, 최신 값, 평균)을 돌려주고, 장소 좌표를 모르거나 조건에 맞는 센서가 없으면 None 이다.
    """
    if spot.latitude is None or spot.longitude is None:
        return None
    near = []
    for sensor in db.scalars(
        select(models.Sensor).where(
            models.Sensor.latitude.is_not(None), models.Sensor.longitude.is_not(None)
        )
    ):
        if sensor.id in exclude_ids:
            continue
        distance = processing.distance_m(spot.latitude, spot.longitude, sensor.latitude, sensor.longitude)
        if distance <= config.MAX_BORROW_METERS:
            near.append((distance, sensor))
    for _, sensor in sorted(near, key=lambda item: item[0]):
        latest, average = _sensor_environment(db, sensor.id)
        if latest is not None and not latest.is_stale:
            return sensor, latest, average
    return None


def _spot_environment(
    db: Session, spot: models.Spot
) -> tuple[schemas.ReadingOut | None, schemas.AverageOut | None, schemas.ValueSourceOut | None]:
    """장소가 보여줄 온도. 연결된 센서를 우선순위 순서로 보고 최신 정상 값이 있는 센서를 고른다.

    - 오래되지 않은(is_stale 가 아닌) 값이 있는 첫 센서를 쓴다.
    - 전부 오래됐다면 가장 최근 값을 낸 센서를 쓴다. (오래됨 표시가 붙은 채로)
    - 정상 값이 하나도 없으면 값 없음(None)이다.
    """
    candidates = []  # (센서, 장소에 설치된 센서인가, 최신 값, 평균)
    for link in spot.sensor_links:
        latest, average = _sensor_environment(db, link.sensor_id)
        if latest is not None:
            candidates.append((link.sensor, link.on_site, latest, average))

    # 연결된 센서에서 오래되지 않은 값을 얻지 못하면, 가까운 센서의 값을 빌린다
    if not any(not c[2].is_stale for c in candidates):
        borrowed = _borrow_nearby_sensor(db, spot, {link.sensor_id for link in spot.sensor_links})
        if borrowed is not None:
            candidates.append((*borrowed[:1], False, *borrowed[1:]))
    if not candidates:
        return None, None, None

    chosen = next((c for c in candidates if not c[2].is_stale), None)
    if chosen is None:
        chosen = max(candidates, key=lambda c: c[2].measured_at)
    sensor, on_site, latest, average = chosen
    distance = None
    if None not in (spot.latitude, spot.longitude, sensor.latitude, sensor.longitude):
        distance = round(
            processing.distance_m(spot.latitude, spot.longitude, sensor.latitude, sensor.longitude)
        )
    source = schemas.ValueSourceOut(
        sensor_id=sensor.id,
        ble_name=sensor.ble_name,
        lab_name=sensor.lab_name,
        location=sensor.location,
        is_on_site=on_site,
        distance_m=distance,
    )
    return latest, average, source


def _spot_out(db: Session, s: models.Spot) -> schemas.SpotOut:
    latest, average, source = _spot_environment(db, s)
    on_site = [link.sensor for link in s.sensor_links if link.on_site]
    return schemas.SpotOut(
        id=s.id,
        name=s.name,
        name_en=s.name_en,
        description=s.description,
        description_en=s.description_en,
        latitude=s.latitude,
        longitude=s.longitude,
        arrival=schemas.ArrivalOut(
            method="ble_auto" if on_site else "manual",
            sensors=[
                schemas.SpotSensorOut(
                    sensor_id=x.id, ble_name=x.ble_name, mac=x.mac, location=x.location
                )
                for x in on_site
            ],
        ),
        latest_reading=latest,
        recent_average=average,
        value_source=source,
    )


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/api/lab-sync/status", response_model=schemas.LabSyncStatusOut)
def lab_sync_status():
    """연구실 센서 데이터를 가져오는 작업의 마지막 결과. 연동이 잘 되는지 점검하는 용도."""
    return schemas.LabSyncStatusOut(
        enabled=config.LAB_SYNC_ENABLED,
        interval_seconds=config.LAB_SYNC_SECONDS,
        url=config.LAB_SYNC_URL,
        **lab_sync.state,
    )


def _find_sensor(db: Session, mac: str | None, ble_name: str) -> models.Sensor | None:
    """업로드된 센서를 찾는다. MAC 이 있으면 MAC 으로만 찾고, 없으면(iOS) BLE 이름으로 찾는다.

    MAC 이 있는데 등록된 MAC 과 다르면, 이름이 같아도 다른 기기(다른 팀 센서 등)일 수 있어 찾지 않는다.
    """
    if mac:
        return db.scalars(select(models.Sensor).where(models.Sensor.mac == mac)).first()
    return db.scalars(select(models.Sensor).where(models.Sensor.ble_name == ble_name)).first()


@app.post(
    "/api/readings",
    response_model=schemas.ReadingResult,
    status_code=201,
    responses={404: {"description": "등록되지 않은 센서. 재시도하지 않아도 된다"}},
)
def post_reading(body: schemas.ReadingIn, response: Response, db: Session = Depends(get_db)):
    """측정값 업로드. 같은 센서의 같은 measured_at 이 다시 오면 200 + duplicate (앱 재전송 대응)."""
    sensor = _find_sensor(db, body.mac, body.ble_name)
    if sensor is None:
        raise HTTPException(404, "등록되지 않은 센서입니다")

    exists = db.scalars(
        select(models.Reading.id).where(
            models.Reading.sensor_id == sensor.id,
            models.Reading.measured_at == body.measured_at,
        )
    ).first()
    if exists is not None:
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")

    db.add(
        models.Reading(
            sensor_id=sensor.id,
            measured_at=body.measured_at,
            temperature=body.temperature,
            humidity=body.humidity,
            aqi=body.aqi,
            tvoc=body.tvoc,
            eco2=body.eco2,
            rssi=body.rssi,
            received_at=int(time.time()),
        )
    )
    try:
        db.commit()
    except IntegrityError:  # 동시 요청으로 같은 키가 먼저 들어간 경우
        db.rollback()
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")
    return schemas.ReadingResult(status="created")


@app.get("/api/sensors", response_model=list[schemas.SensorOut])
def list_sensors(kind: str | None = None, db: Session = Depends(get_db)):
    """등록된 센서 목록. kind=opensrc 또는 kind=lab 으로 거를 수 있다."""
    query = select(models.Sensor).order_by(models.Sensor.id)
    if kind:
        query = query.where(models.Sensor.kind == kind)
    sensors = db.scalars(query).all()
    return [
        schemas.SensorOut(
            sensor_id=s.id,
            kind=s.kind,
            ble_name=s.ble_name,
            mac=s.mac,
            location=s.location,
            lab_name=s.lab_name,
            latitude=s.latitude,
            longitude=s.longitude,
        )
        for s in sensors
    ]


@app.get("/api/sensors/status", response_model=list[schemas.SensorStatusOut])
def sensors_status(kind: str | None = None, db: Session = Depends(get_db)):
    """센서별 상태. 등록된 센서, 장소에 연결된 센서, 측정값을 보낸 적 있는 센서를 모두 보여준다.

    kind=opensrc 또는 kind=lab 으로 거르면 등록된 해당 종류의 센서만 보여준다.
    """
    registered = {s.id: s for s in db.scalars(select(models.Sensor)).all()}
    spot_names: dict[int, list[str]] = {}
    for s in db.scalars(select(models.Spot).order_by(models.Spot.id)):
        for link in s.sensor_links:
            names = spot_names.setdefault(link.sensor_id, [])
            if s.name not in names:
                names.append(s.name)
    reported = set(db.scalars(select(models.Reading.sensor_id).distinct()).all())

    sensor_ids = sorted(set(registered) | set(spot_names) | reported)
    if kind:
        sensor_ids = [i for i in sensor_ids if i in registered and registered[i].kind == kind]

    result = []
    for sensor_id in sensor_ids:
        sensor = registered.get(sensor_id)
        latest, _ = _sensor_environment(db, sensor_id)
        last_received, received_count = db.execute(
            select(func.max(models.Reading.received_at), func.count()).where(
                models.Reading.sensor_id == sensor_id
            )
        ).one()
        result.append(
            schemas.SensorStatusOut(
                sensor_id=sensor_id,
                kind=sensor.kind if sensor else None,
                ble_name=sensor.ble_name if sensor else None,
                mac=sensor.mac if sensor else None,
                location=sensor.location if sensor else None,
                spot_names=spot_names.get(sensor_id, []),
                status=processing.sensor_status(
                    has_data=latest is not None,
                    is_stale=latest.is_stale if latest else False,
                    is_suspect=latest.is_suspect if latest else False,
                    only_invalid=latest is None and received_count > 0,
                ),
                latest_reading=latest,
                last_received_at=last_received,
            )
        )
    return result


@app.get("/api/spots", response_model=list[schemas.SpotOut])
def list_spots(db: Session = Depends(get_db)):
    spots = db.scalars(select(models.Spot).order_by(models.Spot.id)).all()
    return [_spot_out(db, s) for s in spots]


@app.get("/api/spots/{spot_id}", response_model=schemas.SpotOut)
def get_spot(spot_id: int, db: Session = Depends(get_db)):
    spot = db.get(models.Spot, spot_id)
    if spot is None:
        raise HTTPException(404, "spot not found")
    return _spot_out(db, spot)


@app.get("/api/courses", response_model=list[schemas.CourseOut])
def list_courses(db: Session = Depends(get_db)):
    courses = db.scalars(
        select(models.Course)
        .options(selectinload(models.Course.stops).selectinload(models.CourseSpot.spot))
        .order_by(models.Course.id)
    ).all()
    return [
        schemas.CourseOut(
            id=c.id,
            name=c.name,
            name_en=c.name_en,
            description=c.description,
            total_minutes=sum(cs.minutes for cs in c.stops),
            spots=[
                schemas.CourseSpotOut(
                    position=cs.position, minutes=cs.minutes, spot=_spot_out(db, cs.spot)
                )
                for cs in c.stops
            ],
        )
        for c in courses
    ]


@app.get("/api/courses/recommend", response_model=recommend.RecommendationOut)
def recommend_course(db: Session = Depends(get_db)):
    """현재 기온에 맞는 코스를 추천한다. 춥거나 더우면 가장 짧은 코스, 쾌적하면 가장 긴 코스."""
    courses = db.scalars(
        select(models.Course)
        .options(selectinload(models.Course.stops).selectinload(models.CourseSpot.spot))
        .order_by(models.Course.id)
    ).all()
    temperatures: dict[int, float | None] = {}
    sensors: dict[int, int | None] = {}  # 장소 -> 그 기온을 낸 센서 (같은 센서를 여러 번 세지 않으려고)
    for c in courses:
        for cs in c.stops:
            if cs.spot_id not in temperatures:
                latest, _, source = _spot_environment(db, cs.spot)
                # 오래된 값은 지금 기온이 아니므로 판단에 쓰지 않는다
                usable = latest is not None and not latest.is_stale
                temperatures[cs.spot_id] = latest.temperature if usable else None
                sensors[cs.spot_id] = source.sensor_id if usable and source else None
    infos = [
        recommend.CourseInfo(
            id=c.id,
            name=c.name,
            total_minutes=sum(cs.minutes for cs in c.stops),
            spot_ids=[cs.spot_id for cs in c.stops],
        )
        for c in courses
    ]
    return recommend.build(infos, temperatures, config.COLD_MAX_CELSIUS, config.HOT_MIN_CELSIUS, sensors)
