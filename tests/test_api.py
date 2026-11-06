import time

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import main, seed
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


def test_spots_and_404(client):
    assert len(client.get("/api/spots").json()) == 5
    assert client.get("/api/spots/999").status_code == 404


def test_courses_total_minutes(client):
    courses = client.get("/api/courses").json()
    assert len(courses) == 2
    for c in courses:
        assert c["total_minutes"] == sum(s["minutes"] for s in c["spots"])
        assert [s["position"] for s in c["spots"]] == list(range(1, len(c["spots"]) + 1))
