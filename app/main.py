import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Response
from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import config, models, processing, schemas
from .database import Base, engine, get_db
from .seed import seed_if_empty


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    seed_if_empty()
    yield


app = FastAPI(title="Campus BLE Tour Guide API", lifespan=lifespan)


def _reading_out(r: models.Reading, now: int, suspect: bool) -> schemas.ReadingOut:
    age = processing.age_minutes(r.measured_at, now)
    return schemas.ReadingOut(
        sensor_id=r.sensor_id,
        seq=r.seq,
        measured_at=r.measured_at,
        temperature=r.temperature,
        humidity=r.humidity,
        age_minutes=age,
        is_stale=processing.is_stale(age, config.STALE_MINUTES),
        is_suspect=suspect,
    )


def _valid_clause():
    """온도와 습도가 모두 0.0인 측정값(센서 오류값)을 뺀 조건. processing.is_valid_reading 과 같은 기준."""
    R = models.Reading
    return or_(R.temperature != 0, R.humidity != 0)


def _is_fixed(db: Session, newest: models.Reading) -> bool:
    """최신 측정값과 온도, 습도가 같은 값이 이어진 구간이 충분히 길면 True.

    같은 값이 시작된 시점은 '마지막으로 다른 값이 측정된 시각' 이후로 본다.
    센서 오류값(0.0)은 구간 계산에서 제외한다.
    """
    R = models.Reading
    last_different = db.scalar(
        select(func.max(R.measured_at)).where(
            R.sensor_id == newest.sensor_id,
            R.measured_at <= newest.measured_at,
            or_(R.temperature != newest.temperature, R.humidity != newest.humidity),
            _valid_clause(),
        )
    )
    in_run = [
        R.sensor_id == newest.sensor_id,
        R.measured_at <= newest.measured_at,
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
        .order_by(models.Reading.measured_at.desc(), models.Reading.seq.desc())
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


def _spot_out(db: Session, s: models.Spot) -> schemas.SpotOut:
    latest, average = _sensor_environment(db, s.sensor_id)
    return schemas.SpotOut(
        id=s.id,
        name=s.name,
        name_en=s.name_en,
        description=s.description,
        description_en=s.description_en,
        sensor_id=s.sensor_id,
        ble_name=s.ble_name,
        latitude=s.latitude,
        longitude=s.longitude,
        latest_reading=latest,
        recent_average=average,
    )


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/readings", response_model=schemas.ReadingResult, status_code=201)
def post_reading(body: schemas.ReadingIn, response: Response, db: Session = Depends(get_db)):
    """측정값 업로드. 같은 (sensor_id, seq)가 다시 오면 200 + duplicate (앱 재전송 대응)."""
    exists = db.scalars(
        select(models.Reading.id).where(
            models.Reading.sensor_id == body.sensor_id, models.Reading.seq == body.seq
        )
    ).first()
    if exists is not None:
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")

    db.add(models.Reading(**body.model_dump(), received_at=int(time.time())))
    try:
        db.commit()
    except IntegrityError:  # 동시 요청으로 같은 키가 먼저 들어간 경우
        db.rollback()
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")
    return schemas.ReadingResult(status="created")


@app.get("/api/sensors/status", response_model=list[schemas.SensorStatusOut])
def sensors_status(db: Session = Depends(get_db)):
    """센서별 상태. 장소에 연결된 센서와 측정값을 보낸 적 있는 센서를 모두 보여준다."""
    spot_names: dict[int, list[str]] = {}
    for s in db.scalars(
        select(models.Spot).where(models.Spot.sensor_id.is_not(None)).order_by(models.Spot.id)
    ):
        spot_names.setdefault(s.sensor_id, []).append(s.name)
    reported = set(db.scalars(select(models.Reading.sensor_id).distinct()).all())

    result = []
    for sensor_id in sorted(set(spot_names) | reported):
        latest, _ = _sensor_environment(db, sensor_id)
        last_received = db.scalar(
            select(func.max(models.Reading.received_at)).where(models.Reading.sensor_id == sensor_id)
        )
        result.append(
            schemas.SensorStatusOut(
                sensor_id=sensor_id,
                spot_names=spot_names.get(sensor_id, []),
                status=processing.sensor_status(
                    has_data=latest is not None,
                    is_stale=latest.is_stale if latest else False,
                    is_suspect=latest.is_suspect if latest else False,
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
