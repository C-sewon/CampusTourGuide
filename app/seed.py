"""더미 데이터. 실제 센서ID/설치 장소가 확정되면 이 파일(또는 DB)을 수정한다."""
import time

from sqlalchemy import select

from . import models
from .database import SessionLocal

# 실제 센서 5대 (수업에서 공유된 정보). 센서 N 은 BLE 이름 opensrcN 이다.
SENSORS = [
    # (번호, BLE 이름, MAC, 설치 위치)
    (1, "opensrc1", "88:A2:9E:9B:5F:09", "도서관 뒤편 (진로창업본부 입구)"),
    (2, "opensrc2", "88:A2:9E:9B:5E:6A", "사회과학대 입구"),
    (3, "opensrc3", "B8:27:EB:D3:40:06", "사회과학대 옆 이마트24 건너편"),
    (4, "opensrc4", "D8:3A:DD:79:8F:80", "테니스장 앞"),
    (5, "opensrc5", "D8:3A:DD:C1:89:5B", "테니스장 건너편"),
]

SPOTS = [
    # (이름, 영문, 설명, sensor_id)
    ("솔못", "Solmot Pond", "캠퍼스 중앙의 연못. 산책과 휴식 장소.", 1),
    ("축제 광장", "Festival Plaza", "축제와 행사가 열리는 광장.", 2),
    ("법대 앞 잔디밭", "Law School Lawn", "넓은 잔디밭. 피크닉 명소.", 3),
    ("중앙도서관", "Central Library", "캠퍼스의 대표 학습 공간.", 4),
    ("정문", "Main Gate", "캠퍼스 투어의 시작점.", 5),
]

# 코스: (이름, 영문, 설명, [(spot 순번 1~5, 분)])
COURSES = [
    ("짧은 코스", "Short Course", "더위/추위에 추천하는 짧은 코스", [(5, 5), (1, 10), (3, 10)]),
    (
        "기본 코스",
        "Standard Course",
        "주요 장소를 도는 기본 코스",
        [(5, 5), (1, 10), (2, 10), (3, 10), (4, 10)],
    ),
]


def _seed_sensors(db) -> None:
    """등록되지 않은 센서만 추가한다. 이미 만들어진 DB에도 안전하게 실행된다."""
    existing = set(db.scalars(select(models.Sensor.id)).all())
    for sensor_id, ble_name, mac, location in SENSORS:
        if sensor_id not in existing:
            db.add(models.Sensor(id=sensor_id, ble_name=ble_name, mac=mac, location=location))
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
                    seq=1,
                    measured_at=now - 120,
                    temperature=20.0 + sid,
                    humidity=50.0 + sid,
                    received_at=now,
                )
            )
        db.commit()
