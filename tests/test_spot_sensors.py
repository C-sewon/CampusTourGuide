"""장소와 센서 연결 구조 (#2 합의): 센서 여러 개 묶기, 우선순위, 값의 출처, 도착 방식."""
import time

from sqlalchemy import delete

from app import config, models, seed
from tests.test_api import add_lab_reading, client, reading  # noqa: F401  (client 는 pytest 픽스처)

TENNIS, NO_SENSOR = 4, 5  # 시드 장소 번호


def drop_readings(sensor_id):
    """센서의 측정값을 모두 지워 '값이 없는 센서' 상태를 만든다."""
    with seed.SessionLocal() as db:
        db.execute(delete(models.Reading).where(models.Reading.sensor_id == sensor_id))
        db.commit()


def test_sensor_spot_uses_ble_auto_arrival_with_its_sensor(client):
    spot = client.get("/api/spots/1").json()
    assert spot["arrival"]["method"] == "ble_auto"
    assert [s["ble_name"] for s in spot["arrival"]["sensors"]] == ["opensrc1"]
    assert spot["arrival"]["sensors"][0]["mac"] == "88:A2:9E:9B:5F:09"


def test_spot_without_sensor_uses_manual_arrival(client):
    spot = client.get(f"/api/spots/{NO_SENSOR}").json()
    assert spot["arrival"] == {"method": "manual", "sensors": []}


def test_tennis_spot_merges_sensor_4_and_5(client):
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["name"] == "테니스장"
    assert [s["ble_name"] for s in spot["arrival"]["sensors"]] == ["opensrc4", "opensrc5"]
    assert spot["arrival"]["method"] == "ble_auto"


def test_first_sensor_is_used_when_both_are_fresh(client):
    spot = client.get(f"/api/spots/{TENNIS}").json()  # 시드: 센서 4 = 24.0, 센서 5 = 25.0 (모두 2분 전)
    assert spot["value_source"]["sensor_id"] == 4
    assert spot["value_source"]["is_on_site"] is True
    assert spot["latest_reading"]["temperature"] == 24.0


def test_next_sensor_is_used_when_the_first_has_no_value(client):
    drop_readings(4)  # 센서 4 가 죽은 상황 (opensrc4)
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["value_source"]["sensor_id"] == 5
    assert spot["value_source"]["location"] == "테니스장 건너편"
    assert spot["latest_reading"]["temperature"] == 25.0


def test_next_sensor_is_used_when_the_first_has_only_error_values(client):
    now = int(time.time())
    # 센서 4 가 0.0 / 0.0 만 올리고 시드 값은 지워진 상황 (실제 센서 3, 4 의 증상)
    drop_readings(4)
    client.post("/api/readings", json=reading(sensor_id=4, measured_at=now - 10, temperature=0.0, humidity=0.0))
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["value_source"]["sensor_id"] == 5


def test_fresh_sensor_wins_over_a_stale_higher_priority_sensor(client, monkeypatch):
    monkeypatch.setattr(config, "STALE_MINUTES", 2)  # 시드(2분 전)가 오래된 값이 된다
    now = int(time.time())
    client.post("/api/readings", json=reading(sensor_id=5, measured_at=now - 5, temperature=19.5))
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["value_source"]["sensor_id"] == 5  # 센서 4 는 오래됨, 센서 5 는 최신
    assert spot["latest_reading"]["temperature"] == 19.5
    assert spot["latest_reading"]["is_stale"] is False


def test_most_recent_value_is_shown_as_stale_when_every_sensor_is_stale(client, monkeypatch):
    monkeypatch.setattr(config, "STALE_MINUTES", 1)  # 1분 이상 된 값은 모두 오래된 값
    now = int(time.time())
    # 센서 4 는 시드(2분 전), 센서 5 는 더 최근(65초 전)이지만 둘 다 오래된 값이다
    add_lab_reading(5, now - 65, 20.0)
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["latest_reading"]["is_stale"] is True
    assert spot["value_source"]["sensor_id"] == 5  # 더 최근에 값을 낸 쪽
    assert spot["latest_reading"]["temperature"] == 20.0


def test_spot_without_value_has_no_source(client, monkeypatch):
    monkeypatch.setattr(config, "MAX_BORROW_METERS", 1)  # 가까운 센서 빌리기를 끈다 (test_borrow_nearby_sensors.py)
    drop_readings(4)
    drop_readings(5)
    spot = client.get(f"/api/spots/{TENNIS}").json()
    assert spot["latest_reading"] is None
    assert spot["recent_average"] is None
    assert spot["value_source"] is None
    assert spot["arrival"]["method"] == "ble_auto"  # 값이 없어도 도착 방식은 그대로


def test_spot_without_sensor_borrows_a_nearby_sensor_value(client):
    spot = client.get(f"/api/spots/{NO_SENSOR}").json()
    assert spot["value_source"]["sensor_id"] == 2  # 시드: 사회과학대 입구 센서를 빌림
    assert spot["value_source"]["is_on_site"] is False
    assert spot["value_source"]["location"] == "사회과학대 입구"
    assert spot["latest_reading"]["temperature"] == 22.0


def test_sensor_status_lists_every_spot_that_uses_a_sensor(client):
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status").json()}
    assert by_id[2]["spot_names"] == ["사회과학대 입구", "[샘플] 센서 없는 장소"]  # 설치 장소와 빌려 쓰는 장소
    assert by_id[4]["spot_names"] == ["테니스장"]
    assert by_id[5]["spot_names"] == ["테니스장"]


def test_courses_include_the_new_spot_fields(client):
    spots = client.get("/api/courses").json()[1]["spots"]  # 기본 코스
    tennis = next(s["spot"] for s in spots if s["spot"]["name"] == "테니스장")
    assert [s["ble_name"] for s in tennis["arrival"]["sensors"]] == ["opensrc4", "opensrc5"]
    assert tennis["value_source"]["sensor_id"] == 4


def test_old_spots_table_is_detected():
    import pytest
    from sqlalchemy import create_engine, text

    from app.database import ensure_current_schema

    old = create_engine("sqlite://")
    with old.begin() as conn:  # 장소가 센서를 하나만 쓰던 예전 구조
        conn.execute(text("CREATE TABLE spots (id INTEGER PRIMARY KEY, name TEXT, sensor_id INTEGER)"))
    with pytest.raises(RuntimeError, match="spots"):
        ensure_current_schema(old)
