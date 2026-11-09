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


def reading(**kw):
    base = dict(sensor_id=1, seq=100, measured_at=int(time.time()), temperature=23.5, humidity=48.2)
    return {**base, **kw}


def test_post_reading_created_then_duplicate(client):
    assert client.post("/api/readings", json=reading()).status_code == 201
    r = client.post("/api/readings", json=reading())
    assert r.status_code == 200 and r.json()["status"] == "duplicate"


def test_post_reading_validation(client):
    assert client.post("/api/readings", json=reading(humidity=150)).status_code == 422
    assert client.post("/api/readings", json=reading(sensor_id=300)).status_code == 422


def test_spot_shows_latest_reading_with_age(client):
    client.post(
        "/api/readings",
        json=reading(seq=200, measured_at=int(time.time()) - 300, temperature=30.0),
    )
    # 시드 측정값(2분 전)이 더 최신이므로 그쪽이 반환된다
    assert client.get("/api/spots/1").json()["latest_reading"]["temperature"] == 21.0

    client.post(
        "/api/readings",
        json=reading(seq=201, measured_at=int(time.time()) - 60, temperature=30.0),
    )
    latest = client.get("/api/spots/1").json()["latest_reading"]
    assert latest["temperature"] == 30.0
    assert latest["age_minutes"] == 1


def test_stale_flag_follows_threshold(client, monkeypatch):
    # 시드 측정값은 2분 전
    assert client.get("/api/spots/1").json()["latest_reading"]["is_stale"] is False
    monkeypatch.setattr(config, "STALE_MINUTES", 2)
    assert client.get("/api/spots/1").json()["latest_reading"]["is_stale"] is True


def test_fixed_value_sensor_is_flagged_and_not_averaged(client):
    now = int(time.time())
    for i in range(5):  # 같은 값이 5번 연속
        r = client.post(
            "/api/readings",
            json=reading(sensor_id=2, seq=500 + i, measured_at=now - 50 + i, temperature=18.0, humidity=40.0),
        )
        assert r.status_code == 201

    spot = client.get("/api/spots/2").json()
    assert spot["latest_reading"]["is_suspect"] is True
    assert spot["recent_average"] is None
    # 값은 버리지 않고 그대로 저장되어 있다
    assert spot["latest_reading"]["temperature"] == 18.0

    # 다른 센서는 영향 없음
    assert client.get("/api/spots/1").json()["latest_reading"]["is_suspect"] is False


def test_varying_values_are_not_flagged(client):
    now = int(time.time())
    for i in range(5):
        client.post(
            "/api/readings",
            json=reading(sensor_id=2, seq=600 + i, measured_at=now - 50 + i, temperature=18.0 + i * 0.1),
        )
    assert client.get("/api/spots/2").json()["latest_reading"]["is_suspect"] is False


def test_recent_average_uses_window_only(client):
    now = int(time.time())
    # 시드: 센서 3 은 23.0 / 53.0 (2분 전). 창 안에 25.0 / 55.0 추가, 창 밖(2시간 전) 값은 제외
    client.post("/api/readings", json=reading(sensor_id=3, seq=700, measured_at=now - 60, temperature=25.0, humidity=55.0))
    client.post("/api/readings", json=reading(sensor_id=3, seq=701, measured_at=now - 7200, temperature=40.0, humidity=10.0))

    avg = client.get("/api/spots/3").json()["recent_average"]
    assert avg["count"] == 2
    assert avg["temperature"] == 24.0
    assert avg["humidity"] == 54.0
    assert avg["window_minutes"] == 30


def test_spots_and_404(client):
    assert len(client.get("/api/spots").json()) == 5
    assert client.get("/api/spots/999").status_code == 404


def test_courses_total_minutes(client):
    courses = client.get("/api/courses").json()
    assert len(courses) == 2
    for c in courses:
        assert c["total_minutes"] == sum(s["minutes"] for s in c["spots"])
        assert [s["position"] for s in c["spots"]] == list(range(1, len(c["spots"]) + 1))
