import time
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import config, database, lab_sync, main, models, seed
from app.database import Base

NOW = int(time.time())


def lab_time(unix_seconds: int) -> str:
    """Unix 초를 연구실 API 형식(KST 문자열)으로 만든다. 예: Tue Nov 10 07:09:55 2026"""
    kst = datetime.fromtimestamp(unix_seconds, tz=timezone(timedelta(hours=9)))
    # strftime 의 요일과 월 이름은 운영체제 언어에 따라 달라져서 직접 만든다
    weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    return (
        f"{weekdays[kst.weekday()]} {months[kst.month - 1]} {kst.day:02d} "
        f"{kst:%H:%M:%S} {kst.year}"
    )


def row(sensor="sensor 07", temperature=22, seconds_ago=30, fresh=True, mode="direct"):
    return {
        "sensor": sensor,
        "latitude": 36.628895,
        "longitude": 127.457839,
        "temperature": temperature,
        "co2": 1084,
        "time": lab_time(NOW - seconds_ago),
        "fresh": fresh,
        "mode": mode,
    }


@pytest.fixture()
def db(monkeypatch):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(seed, "SessionLocal", Session)
    monkeypatch.setattr(database, "SessionLocal", Session)
    seed.seed_if_empty()
    with Session() as session:
        yield session


def count_readings(db, source=None):
    q = select(func.count()).select_from(models.Reading)
    if source:
        q = q.where(models.Reading.source == source)
    return db.scalar(q)


def test_parse_lab_time_reads_kst():
    # 문서의 예시 Tue Nov 10 07:09:55 2026 (KST) = 2026-11-09 22:09:55 UTC
    expected = int(datetime(2026, 11, 9, 22, 9, 55, tzinfo=timezone.utc).timestamp())
    assert lab_sync.parse_lab_time("Tue Nov 10 07:09:55 2026") == expected
    assert lab_sync.parse_lab_time("Sun Nov  8 14:59:58 2026") is not None  # 한 자리 날짜
    assert lab_sync.parse_lab_time("엉뚱한 문자열") is None
    assert lab_sync.parse_lab_time("Tue Foo 10 07:09:55 2026") is None
    assert lab_sync.parse_lab_time("Tue Feb 31 07:09:55 2026") is None  # 없는 날짜


def test_normalize_lab_name_matches_lab_server_rule():
    for name in ("sensor1", "sensor 1", "sensor01", " Sensor 01 "):
        assert lab_sync.normalize_lab_name(name) == "sensor 01"
    assert lab_sync.normalize_lab_name("sensor 33") == "sensor 33"
    assert lab_sync.normalize_lab_name("pws01") is None
    assert lab_sync.normalize_lab_name("opensrc1") is None


def test_sync_stores_fresh_registered_sensor_without_humidity(db):
    before = count_readings(db, "lab")
    result = lab_sync.sync_rows(db, [row(temperature=22)], now=NOW)
    assert (result.fetched, result.stored) == (1, 1)
    assert count_readings(db, "lab") == before + 1

    saved = db.scalars(select(models.Reading).where(models.Reading.source == "lab")).one()
    assert saved.sensor_id == 1  # sensor 07 = opensrc1
    assert saved.temperature == 22.0
    assert saved.humidity is None
    assert saved.measured_at == NOW - 30


def test_sync_is_idempotent_and_dedupes(db):
    rows = [row(), row(mode="mobile")]  # 같은 센서가 direct 와 mobile 로 따로 나올 수 있다
    first = lab_sync.sync_rows(db, rows, now=NOW)
    second = lab_sync.sync_rows(db, rows, now=NOW)
    assert (first.stored, first.duplicate) == (1, 1)
    assert (second.stored, second.duplicate) == (0, 2)
    assert count_readings(db, "lab") == 1


def test_sync_skips_unknown_sensors_and_stale_rows(db):
    rows = [
        row(sensor="sensor 18"),  # 등록되지 않은 센서
        row(sensor="sensor 31", fresh=False),  # 1시간 이내 갱신이 아님 (opensrc3)
        row(sensor="pws01"),  # 이름 형식이 다름
    ]
    result = lab_sync.sync_rows(db, rows, now=NOW)
    assert (result.unknown_sensor, result.not_fresh, result.stored) == (2, 1, 0)
    assert count_readings(db, "lab") == 0


def test_sync_skips_unreadable_and_future_rows(db):
    bad_time = row()
    bad_time["time"] = "엉뚱한 시각"
    no_temp = row()
    del no_temp["temperature"]
    future = row(seconds_ago=-3600)  # 1시간 뒤의 시각
    result = lab_sync.sync_rows(db, [bad_time, no_temp, future], now=NOW)
    assert (result.invalid, result.stored) == (3, 0)


def test_sync_fills_missing_coordinates_only(db):
    sensor = db.get(models.Sensor, 1)
    sensor.latitude = sensor.longitude = None
    db.commit()
    lab_sync.sync_rows(db, [row(fresh=False)], now=NOW)  # 오래된 값이라도 좌표는 채운다
    assert db.get(models.Sensor, 1).latitude == 36.628895

    db.get(models.Sensor, 1).latitude = 1.0  # 이미 있는 값은 덮어쓰지 않는다
    db.commit()
    lab_sync.sync_rows(db, [row()], now=NOW)
    assert db.get(models.Sensor, 1).latitude == 1.0


def test_synced_temperature_is_served_by_the_spot_api(db, monkeypatch):
    from fastapi.testclient import TestClient

    def override():
        yield db

    main.app.dependency_overrides[database.get_db] = override
    try:
        lab_sync.sync_rows(db, [row(temperature=19, seconds_ago=5)], now=NOW)
        latest = TestClient(main.app).get("/api/spots/1").json()["latest_reading"]
    finally:
        main.app.dependency_overrides.clear()
    assert latest["temperature"] == 19.0
    assert latest["humidity"] is None
    assert latest["is_suspect"] is False


def test_run_once_records_success(db, monkeypatch):
    monkeypatch.setattr(lab_sync, "fetch_rows", lambda *a, **k: [row()])
    lab_sync.state.update(last_attempt=None, last_success=None, last_error="이전 오류", last_result=None)
    lab_sync.run_once()
    assert lab_sync.state["last_error"] is None
    assert lab_sync.state["last_success"] is not None
    assert lab_sync.state["last_result"]["stored"] == 1


def test_run_once_survives_lab_server_failure(db, monkeypatch):
    def broken(*args, **kwargs):
        raise ConnectionError("연구실 서버에 연결할 수 없음")

    monkeypatch.setattr(lab_sync, "fetch_rows", broken)
    lab_sync.run_once()  # 예외를 던지지 않는다
    assert "ConnectionError" in lab_sync.state["last_error"]


def test_lab_sync_status_endpoint(monkeypatch):
    from fastapi.testclient import TestClient

    monkeypatch.setattr(config, "LAB_SYNC_ENABLED", False)
    body = TestClient(main.app).get("/api/lab-sync/status").json()
    assert body["enabled"] is False
    assert body["interval_seconds"] == config.LAB_SYNC_SECONDS
    assert body["url"].endswith("/sensor/api/map")
