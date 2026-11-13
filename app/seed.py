"""더미 데이터. 실제 센서ID/설치 장소가 확정되면 이 파일(또는 DB)을 수정한다."""
import time

from sqlalchemy import select

from . import models
from .database import SessionLocal

# 실제 센서 5대 (수업에서 공유된 정보). 센서 N 은 BLE 이름 opensrcN 이다.
# 연구실 이름(lab_name)과 좌표는 연구실 공용 API(/sensor/api/map)에서 확인한 값이다. (부록 C 대응표)
SENSORS = [
    # (번호, BLE 이름, MAC, 설치 위치, 연구실 이름, 위도, 경도)
    (1, "opensrc1", "88:A2:9E:9B:5F:09", "도서관 뒤편 (진로창업본부 입구)", "sensor 07", 36.628895, 127.457839),
    (2, "opensrc2", "88:A2:9E:9B:5E:6A", "사회과학대 입구", "sensor 15", 36.62974, 127.456406),
    (3, "opensrc3", "B8:27:EB:D3:40:06", "사회과학대 옆 이마트24 건너편", "sensor 31", 36.629309, 127.45748),
    (4, "opensrc4", "D8:3A:DD:79:8F:80", "테니스장 앞", "sensor 32", 36.63027, 127.455806),
    (5, "opensrc5", "D8:3A:DD:C1:89:5B", "테니스장 건너편", "sensor 33", 36.630931, 127.454222),
]

# [샘플] 센서 설치 위치를 기준으로 만든 임시 장소. 설명과 코스는 샘플이다.
# 실제 장소 콘텐츠(솔못 등)는 장소 답사 후 교체한다.
SPOTS = [
    # (이름, 영문, 설명, sensor_id)
    ("도서관 뒤편", "Behind the Library", "[샘플] 진로창업본부 입구가 있는 곳.", 1),
    ("사회과학대 입구", "Social Sciences Entrance", "[샘플] 사회과학대학 입구.", 2),
    ("이마트24 건너편", "Across from E-Mart24", "[샘플] 사회과학대 옆 길 건너편.", 3),
    ("테니스장 앞", "In Front of the Tennis Courts", "[샘플] 테니스장 앞 길.", 4),
    ("테니스장 건너편", "Across from the Tennis Courts", "[샘플] 테니스장 길 건너편.", 5),
]

# [샘플] 코스: (이름, 영문, 설명, [(spot 순번 1~5, 분)])
COURSES = [
    ("짧은 코스", "Short Course", "[샘플] 더위/추위에 추천하는 짧은 코스", [(1, 5), (2, 10), (4, 10)]),
    (
        "기본 코스",
        "Standard Course",
        "[샘플] 장소를 모두 도는 기본 코스",
        [(1, 5), (2, 10), (3, 10), (4, 10), (5, 10)],
    ),
]


def _seed_sensors(db) -> None:
    """센서를 등록하고, 이미 있는 센서에 비어 있는 연구실 이름과 좌표를 채운다.

    이미 만들어진 DB에도 안전하게 실행된다. 이미 채워진 값은 덮어쓰지 않는다.
    """
    existing = {s.id: s for s in db.scalars(select(models.Sensor)).all()}
    for sensor_id, ble_name, mac, location, lab_name, lat, lon in SENSORS:
        sensor = existing.get(sensor_id)
        if sensor is None:
            db.add(
                models.Sensor(
                    id=sensor_id, ble_name=ble_name, mac=mac, location=location,
                    lab_name=lab_name, latitude=lat, longitude=lon,
                )
            )
            continue
        if sensor.lab_name is None:
            sensor.lab_name = lab_name
        if sensor.latitude is None:
            sensor.latitude, sensor.longitude = lat, lon
    db.commit()


def seed_if_empty() -> None:
    with SessionLocal() as db:
        _seed_sensors(db)
        if db.scalars(select(models.Spot.id).limit(1)).first() is not None:
            return
        spots = [
            models.Spot(name=n, name_en=e, description=d, sensor_id=sid) for n, e, d, sid in SPOTS
        ]
        db.add_all(spots)
        db.flush()

        for n, e, d, stops in COURSES:
            db.add(
                models.Course(
                    name=n,
                    name_en=e,
                    description=d,
                    stops=[
                        models.CourseSpot(spot_id=spots[i - 1].id, position=pos, minutes=m)
                        for pos, (i, m) in enumerate(stops, start=1)
                    ],
                )
            )

        now = int(time.time())
        for sid in range(1, 6):  # 센서마다 샘플 측정값 1건
            db.add(
                models.Reading(
                    sensor_id=sid,
                    measured_at=now - 120,
                    temperature=20.0 + sid,
                    humidity=50.0 + sid,
                    received_at=now,
                )
            )
        db.commit()
