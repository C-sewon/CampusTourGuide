"""GET /api/courses/recommend: 현재 기온에 맞는 코스 추천."""
from app import config
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_spot_sensors import drop_readings


def test_default_seed_is_comfortable_and_recommends_the_longest_course(client):
    body = client.get("/api/courses/recommend").json()
    assert body["condition"] == "comfortable"
    assert 20 <= body["temperature"] <= 25
    assert body["sample_count"] >= 1
    assert body["thresholds"] == {"cold_max": 5, "hot_min": 27}
    longest = max(client.get("/api/courses").json(), key=lambda c: c["total_minutes"])
    assert body["recommended_course_id"] == longest["id"]
    assert body["recommended_course_name"] == longest["name"]
    assert "활동하기 좋아" in body["reason"]


def test_courses_list_has_one_recommended_entry_with_totals(client):
    body = client.get("/api/courses/recommend").json()
    totals = {c["id"]: c["total_minutes"] for c in client.get("/api/courses").json()}
    assert {c["id"]: c["total_minutes"] for c in body["courses"]} == totals
    assert sum(c["recommended"] for c in body["courses"]) == 1


def test_cold_day_recommends_the_shortest_course(client, monkeypatch):
    monkeypatch.setattr(config, "COLD_MAX_CELSIUS", 40)  # 시드 기온(약 22도)이 춥다고 판정되게 한다
    body = client.get("/api/courses/recommend").json()
    assert body["condition"] == "cold"
    shortest = min(client.get("/api/courses").json(), key=lambda c: c["total_minutes"])
    assert body["recommended_course_id"] == shortest["id"]
    assert "추워서" in body["reason"]


def test_hot_day_recommends_the_shortest_course(client, monkeypatch):
    monkeypatch.setattr(config, "HOT_MIN_CELSIUS", 10)
    body = client.get("/api/courses/recommend").json()
    assert body["condition"] == "hot"
    shortest = min(client.get("/api/courses").json(), key=lambda c: c["total_minutes"])
    assert body["recommended_course_id"] == shortest["id"]
    assert "더워서" in body["reason"]


def test_reason_mentions_the_temperature_used(client):
    body = client.get("/api/courses/recommend").json()
    assert f"{body['temperature']:.1f}도" in body["reason"]


def test_stale_values_are_not_used_so_the_condition_is_unknown(client, monkeypatch):
    monkeypatch.setattr(config, "STALE_MINUTES", 1)
    monkeypatch.setattr(config, "MAX_BORROW_METERS", 0, raising=False)  # 주변 센서 빌리기 기능이 있으면 끈다
    for sensor_id in range(1, 6):
        drop_readings(sensor_id)
    body = client.get("/api/courses/recommend").json()
    assert body["condition"] == "unknown"
    assert body["temperature"] is None and body["sample_count"] == 0
    assert body["recommended_course_id"] is not None  # 기온을 몰라도 기본 코스는 추천한다
    assert "알 수 없어" in body["reason"]


def test_sensor_shared_by_two_spots_is_counted_once(client):
    # 시드: 센서 2 를 쓰는 사회과학대 입구(장소 2)와 그 값을 빌리는 장소(센서 없는 장소)가 있다
    spots = {s["id"]: s for s in client.get("/api/spots").json()}
    by_sensor = {}
    for s in spots.values():
        if s["latest_reading"] and not s["latest_reading"]["is_stale"] and s["value_source"]:
            by_sensor.setdefault(s["value_source"]["sensor_id"], []).append(s["id"])
    assert any(len(ids) > 1 for ids in by_sensor.values()), "시드에 같은 센서를 쓰는 장소가 있어야 이 테스트가 의미 있음"

    body = client.get("/api/courses/recommend").json()
    in_courses = {cs["spot"]["id"] for c in client.get("/api/courses").json() for cs in c["spots"]}
    distinct = {sid for sid, ids in by_sensor.items() if set(ids) & in_courses}
    assert body["sample_count"] == len(distinct)


def test_a_broken_sensor_reading_does_not_turn_the_day_hot(client):
    """고장 난 센서의 73도 값이 평균을 끌어올려 '덥다'로 추천되지 않는다."""
    from tests.test_api import reading
    import time

    now = int(time.time())
    for sensor_id in range(1, 6):
        client.post("/api/readings", json=reading(sensor_id=sensor_id, measured_at=now - 10, temperature=73.0, humidity=40.0))
    body = client.get("/api/courses/recommend").json()
    assert body["condition"] == "comfortable"
    assert body["temperature"] < 30
