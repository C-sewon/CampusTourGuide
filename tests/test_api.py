import time

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import config, main, seed
from app.database import Base, get_db


@pytest.fixture()
def client(monkeypatch):
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(seed, "SessionLocal", Session)

    def override():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    main.app.dependency_overrides[get_db] = override
    seed.seed_if_empty()
    yield TestClient(main.app)  # lifespan 은 with 문 없이는 실행되지 않음
    main.app.dependency_overrides.clear()


def reading(sensor_id=1, **kw):
    """새 업로드 본문. 센서 N 의 BLE 이름은 opensrcN 이다."""
    base = dict(
        ble_name=f"opensrc{sensor_id}",
        measured_at=int(time.time()),
        temperature=23.5,
        humidity=48.2,
    )
    return {**base, **kw}


def register_sensor(sensor_id):
    """시드에 없는 센서(측정값이 없는 상태)를 테스트용으로 등록한다."""
    from app import models

    with seed.SessionLocal() as db:
        db.add(
            models.Sensor(
                id=sensor_id,
                ble_name=f"opensrc{sensor_id}",
                mac=f"AA:BB:CC:DD:EE:{sensor_id:02X}",
                location="테스트 센서",
            )
        )
        db.commit()


def test_post_reading_created_then_duplicate(client):
    assert client.post("/api/readings", json=reading()).status_code == 201
    r = client.post("/api/readings", json=reading())
    assert r.status_code == 200 and r.json()["status"] == "duplicate"


def test_post_reading_validation(client):
    assert client.post("/api/readings", json=reading(humidity=150)).status_code == 422
    assert client.post("/api/readings", json=reading(mac="not-a-mac")).status_code == 422
    body = reading()
    del body["ble_name"]
    assert client.post("/api/readings", json=body).status_code == 422


def test_post_reading_finds_sensor_by_mac_or_name(client):
    now = int(time.time())
    # Android: MAC 으로 찾는다 (BLE 이름이 달라도 MAC 이 맞으면 같은 센서)
    r = client.post("/api/readings", json=reading(ble_name="다른이름", mac="88:A2:9E:9B:5F:09", measured_at=now - 100))
    assert r.status_code == 201
    # MAC 표기 차이(소문자, 하이픈)는 같은 MAC 으로 본다
    r = client.post("/api/readings", json=reading(mac="88-a2-9e-9b-5f-09", measured_at=now - 100))
    assert r.json()["status"] == "duplicate"
    # iOS: MAC 없이 이름만으로 찾는다
    assert client.post("/api/readings", json=reading(sensor_id=2, measured_at=now - 90)).status_code == 201


def test_post_reading_rejects_unregistered_sensor(client):
    r = client.post("/api/readings", json=reading(ble_name="opensrc99"))
    assert r.status_code == 404
    # 이름은 등록된 센서와 같아도 MAC 이 다르면 다른 기기(다른 팀 센서 등)이므로 거절한다
    r = client.post("/api/readings", json=reading(ble_name="opensrc1", mac="AA:AA:AA:AA:AA:AA"))
    assert r.status_code == 404


def test_post_reading_dedupes_by_sensor_and_measured_at(client):
    now = int(time.time())
    assert client.post("/api/readings", json=reading(measured_at=now - 50, temperature=30.0)).status_code == 201
    again = client.post("/api/readings", json=reading(measured_at=now - 50, temperature=20.0))
    assert again.status_code == 200 and again.json()["status"] == "duplicate"
    # 처음 저장된 값이 유지된다
    assert client.get("/api/spots/1").json()["latest_reading"]["temperature"] == 30.0
    # 같은 시각이라도 다른 센서면 별개의 측정값이다
    assert client.post("/api/readings", json=reading(sensor_id=2, measured_at=now - 50)).status_code == 201


def test_post_reading_stores_air_quality_and_rssi(client):
    from sqlalchemy import select

    from app import models

    now = int(time.time())
    r = client.post(
        "/api/readings",
        json=reading(measured_at=now - 5, aqi=2, tvoc=90, eco2=620, rssi=-58),
    )
    assert r.status_code == 201
    with seed.SessionLocal() as db:
        row = db.scalars(select(models.Reading).where(models.Reading.measured_at == now - 5)).one()
    assert (row.sensor_id, row.aqi, row.tvoc, row.eco2, row.rssi) == (1, 2, 90, 620, -58)


def test_spot_shows_latest_reading_with_age(client):
    client.post(
        "/api/readings",
        json=reading(measured_at=int(time.time()) - 300, temperature=30.0),
    )
    # 시드 측정값(2분 전)이 더 최신이므로 그쪽이 반환된다
    assert client.get("/api/spots/1").json()["latest_reading"]["temperature"] == 21.0

    client.post(
        "/api/readings",
        json=reading(measured_at=int(time.time()) - 60, temperature=30.0),
    )
    latest = client.get("/api/spots/1").json()["latest_reading"]
    assert latest["temperature"] == 30.0
    assert latest["age_minutes"] == 1


def test_stale_flag_follows_threshold(client, monkeypatch):
    # 시드 측정값은 2분 전
    assert client.get("/api/spots/1").json()["latest_reading"]["is_stale"] is False
    monkeypatch.setattr(config, "STALE_MINUTES", 2)
    assert client.get("/api/spots/1").json()["latest_reading"]["is_stale"] is True


def test_fixed_value_sensor_is_flagged_and_not_averaged(client, monkeypatch):
    monkeypatch.setattr(config, "FIXED_VALUE_MINUTES", 0)  # 시간 조건 없이 개수만 본다
    now = int(time.time())
    for i in range(5):  # 같은 값이 5번 연속
        r = client.post(
            "/api/readings",
            json=reading(sensor_id=2, measured_at=now - 50 + i, temperature=18.0, humidity=40.0),
        )
        assert r.status_code == 201

    spot = client.get("/api/spots/2").json()
    assert spot["latest_reading"]["is_suspect"] is True
    assert spot["recent_average"] is None
    # 값은 버리지 않고 그대로 저장되어 있다
    assert spot["latest_reading"]["temperature"] == 18.0

    # 다른 센서는 영향 없음
    assert client.get("/api/spots/1").json()["latest_reading"]["is_suspect"] is False


def test_short_fixed_run_is_not_flagged_by_default(client):
    # 1초 간격 센서는 몇 초 동안 같은 값이 나올 수 있다. 기본 설정(10분)에서는 의심하지 않는다
    now = int(time.time())
    for i in range(8):
        client.post(
            "/api/readings",
            json=reading(sensor_id=2, measured_at=now - 20 + i, temperature=18.0, humidity=40.0),
        )
    assert client.get("/api/spots/2").json()["latest_reading"]["is_suspect"] is False


def test_long_fixed_run_is_flagged(client):
    register_sensor(99)
    # 센서 99는 시드 측정값이 없다. 같은 값이 약 11분(650초) 동안 6번 이어짐
    now = int(time.time())
    for i in range(6):
        client.post(
            "/api/readings",
            json=reading(sensor_id=99, measured_at=now - 720 + 130 * i, temperature=18.0, humidity=40.0),
        )
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[99]["latest_reading"]["is_suspect"] is True
    assert by_id[99]["status"] == "suspect"


def test_fixed_run_restarts_after_a_different_value(client):
    register_sensor(99)
    # 오래 같은 값이었어도 최신 값이 달라졌으면 의심하지 않는다 (센서가 다시 움직임)
    now = int(time.time())
    for i in range(6):
        client.post(
            "/api/readings",
            json=reading(sensor_id=99, measured_at=now - 1500 + 130 * i, temperature=18.0, humidity=40.0),
        )
    client.post("/api/readings", json=reading(sensor_id=99, measured_at=now - 30, temperature=18.2, humidity=40.0))
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[99]["latest_reading"]["is_suspect"] is False


def test_varying_values_are_not_flagged(client):
    now = int(time.time())
    for i in range(5):
        client.post(
            "/api/readings",
            json=reading(sensor_id=2, measured_at=now - 50 + i, temperature=18.0 + i * 0.1),
        )
    assert client.get("/api/spots/2").json()["latest_reading"]["is_suspect"] is False


def test_recent_average_uses_window_only(client):
    now = int(time.time())
    # 시드: 센서 3 은 23.0 / 53.0 (2분 전). 창 안에 25.0 / 55.0 추가, 창 밖(2시간 전) 값은 제외
    client.post("/api/readings", json=reading(sensor_id=3, measured_at=now - 60, temperature=25.0, humidity=55.0))
    client.post("/api/readings", json=reading(sensor_id=3, measured_at=now - 7200, temperature=40.0, humidity=10.0))

    avg = client.get("/api/spots/3").json()["recent_average"]
    assert avg["count"] == 2
    assert avg["temperature"] == 24.0
    assert avg["humidity"] == 54.0
    assert avg["window_minutes"] == 30


def test_sensor_status_lists_all_seeded_sensors(client):
    sensors = client.get("/api/sensors/status").json()
    assert [s["sensor_id"] for s in sensors] == [1, 2, 3, 4, 5]
    assert all(s["status"] == "ok" for s in sensors)
    assert sensors[0]["spot_names"] == ["도서관 뒤편"]
    assert sensors[0]["last_received_at"] is not None


def test_sensor_status_reflects_stale_and_suspect(client, monkeypatch):
    monkeypatch.setattr(config, "FIXED_VALUE_MINUTES", 0)
    now = int(time.time())
    for i in range(5):
        client.post(
            "/api/readings",
            json=reading(sensor_id=2, measured_at=now - 50 + i, temperature=18.0, humidity=40.0),
        )
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[2]["status"] == "suspect"
    assert by_id[1]["status"] == "ok"

    monkeypatch.setattr(config, "STALE_MINUTES", 2)  # 시드 측정값은 2분 전
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[1]["status"] == "stale"


def test_sensor_status_includes_sensor_without_spot(client):
    register_sensor(99)
    client.post("/api/readings", json=reading(sensor_id=99))
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[99]["spot_names"] == []
    assert by_id[99]["status"] == "ok"


def test_spots_and_404(client):
    assert len(client.get("/api/spots").json()) == 5
    assert client.get("/api/spots/999").status_code == 404


def test_courses_total_minutes(client):
    courses = client.get("/api/courses").json()
    assert len(courses) == 2
    for c in courses:
        assert c["total_minutes"] == sum(s["minutes"] for s in c["spots"])
        assert [s["position"] for s in c["spots"]] == list(range(1, len(c["spots"]) + 1))


def test_all_zero_reading_is_stored_but_ignored_for_latest_and_average(client):
    now = int(time.time())
    # 센서 1 은 시드 측정값(21.0 / 51.0, 2분 전)이 있다. 더 최근에 0.0 / 0.0 이 올라와도 무시한다
    r = client.post("/api/readings", json=reading(sensor_id=1, measured_at=now - 10, temperature=0.0, humidity=0.0))
    assert r.status_code == 201  # 저장은 한다

    spot = client.get("/api/spots/1").json()
    assert spot["latest_reading"]["temperature"] == 21.0
    assert spot["recent_average"]["count"] == 1
    assert spot["recent_average"]["temperature"] == 21.0


def test_zero_temperature_with_humidity_is_a_valid_reading(client):
    now = int(time.time())
    client.post("/api/readings", json=reading(sensor_id=1, measured_at=now - 10, temperature=0.0, humidity=62.0))
    assert client.get("/api/spots/1").json()["latest_reading"]["temperature"] == 0.0


def test_zero_readings_do_not_count_toward_fixed_value(client, monkeypatch):
    monkeypatch.setattr(config, "FIXED_VALUE_MINUTES", 0)
    now = int(time.time())
    for i in range(6):  # 0.0 / 0.0 이 6번 연속 와도 고정값으로 세지 않는다
        client.post("/api/readings", json=reading(sensor_id=1, measured_at=now - 50 + i, temperature=0.0, humidity=0.0))
    assert client.get("/api/spots/1").json()["latest_reading"]["is_suspect"] is False


def test_sensor_sending_only_zeros_has_invalid_status(client):
    register_sensor(99)
    now = int(time.time())
    for i in range(3):  # 센서 99 는 0.0 / 0.0 만 보낸다 (센서 3, 4 와 같은 상황)
        client.post("/api/readings", json=reading(sensor_id=99, measured_at=now - 30 + i, temperature=0.0, humidity=0.0))
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[99]["status"] == "invalid"
    assert by_id[99]["latest_reading"] is None
    assert by_id[99]["last_received_at"] is not None  # 통신은 되고 있다는 증거
    assert by_id[1]["status"] == "ok"


def test_real_sensors_are_registered(client):
    from sqlalchemy import select

    from app import models

    with seed.SessionLocal() as db:
        sensors = {s.id: s for s in db.scalars(select(models.Sensor)).all()}
    assert sorted(sensors) == [1, 2, 3, 4, 5]
    assert sensors[4].ble_name == "opensrc4"
    assert sensors[4].mac == "D8:3A:DD:79:8F:80"
    assert sensors[4].location == "테니스장 앞"


def test_seed_sensors_is_idempotent(client):
    from sqlalchemy import func, select

    from app import models

    seed.seed_if_empty()  # 다시 실행해도 센서가 늘어나지 않는다
    with seed.SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(models.Sensor)) == 5


def test_sensors_list_returns_registered_sensors(client):
    sensors = client.get("/api/sensors").json()
    assert [s["sensor_id"] for s in sensors] == [1, 2, 3, 4, 5]
    assert sensors[0]["ble_name"] == "opensrc1"
    assert sensors[0]["mac"] == "88:A2:9E:9B:5F:09"
    assert sensors[3]["location"] == "테니스장 앞"
    assert sensors[0]["latitude"] is None  # 위치 좌표는 현장 측정 후 채운다


def test_sensor_status_includes_name_mac_and_location(client):
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[2]["ble_name"] == "opensrc2"
    assert by_id[2]["mac"] == "88:A2:9E:9B:5E:6A"
    assert by_id[2]["location"] == "사회과학대 입구"


def test_rejected_unregistered_sensor_does_not_appear_in_status(client):
    client.post("/api/readings", json=reading(ble_name="opensrc99"))
    ids = [s["sensor_id"] for s in client.get("/api/sensors/status").json()]
    assert 99 not in ids


def test_registered_sensor_without_readings_is_no_data(client):
    from sqlalchemy import delete

    from app import models

    with seed.SessionLocal() as db:  # 센서 5 의 시드 측정값을 지워 값이 없는 상태를 만든다
        db.execute(delete(models.Reading).where(models.Reading.sensor_id == 5))
        db.commit()
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[5]["status"] == "no_data"
    assert by_id[5]["latest_reading"] is None
    assert by_id[5]["last_received_at"] is None
