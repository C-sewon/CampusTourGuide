"""장소의 센서 값이 없을 때 MAX_BORROW_METERS 안의 가까운 센서 값을 빌리는 동작 (#18, #2)."""
import time

from app import config, processing
from tests.test_api import client, reading  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_lab_sensors_api import add_lab_sensor
from tests.test_spot_sensors import TENNIS, drop_readings

# 시드 장소 3 = 이마트24 건너편 (센서 3 설치 장소). 센서 3 은 실제로 죽어 있는 센서다.
MART = 3
# 실제 좌표 기준: 센서 1(opensrc1)은 센서 3 에서 약 56m, 센서 2(opensrc2)는 약 107m, 센서 4 는 약 184m
SENSOR3 = (36.629309, 127.45748)
SENSOR1 = (36.628895, 127.457839)


def test_distance_m_is_sensible():
    assert processing.distance_m(*SENSOR3, *SENSOR3) == 0
    d = processing.distance_m(*SENSOR1, *SENSOR3)
    assert 50 <= d <= 62  # 연구실 API 좌표로 계산한 약 56m
    assert abs(d - processing.distance_m(*SENSOR3, *SENSOR1)) < 1e-6  # 방향과 무관


def test_sensor_spots_have_coordinates_and_the_sample_spot_without_sensor_has_none(client):
    spots = {s["id"]: s for s in client.get("/api/spots").json()}
    assert (spots[MART]["latitude"], spots[MART]["longitude"]) == SENSOR3  # 센서가 설치된 장소 = 센서 위치
    assert spots[5]["latitude"] is None  # 센서 없는 샘플 장소


def test_on_site_sensor_is_reported_with_distance_zero(client):
    source = client.get("/api/spots/1").json()["value_source"]
    assert source["is_on_site"] is True
    assert source["distance_m"] == 0


def test_dead_sensor_spot_borrows_the_nearest_fresh_sensor(client):
    drop_readings(3)  # 센서 3 은 값이 없다
    spot = client.get(f"/api/spots/{MART}").json()
    source = spot["value_source"]
    assert source["sensor_id"] == 1
    assert source["is_on_site"] is False
    assert 50 <= source["distance_m"] <= 62
    assert source["ble_name"] == "opensrc1"
    assert spot["latest_reading"]["temperature"] == 21.0  # 센서 1 의 값
    assert spot["arrival"]["method"] == "ble_auto"  # 도착 판정은 이 장소에 설치된 센서 기준 그대로


def test_nearest_fresh_sensor_is_chosen_among_neighbors(client):
    drop_readings(3)
    drop_readings(1)  # 가장 가까운 센서 1 도 값이 없다
    source = client.get(f"/api/spots/{MART}").json()["value_source"]
    assert source["sensor_id"] == 2  # 다음으로 가까운 센서 (약 107m)
    assert 100 <= source["distance_m"] <= 115


def test_sensors_beyond_the_max_distance_are_not_borrowed(client, monkeypatch):
    drop_readings(3)
    monkeypatch.setattr(config, "MAX_BORROW_METERS", 50)  # 센서 1 (약 56m)도 너무 멀다
    spot = client.get(f"/api/spots/{MART}").json()
    assert spot["latest_reading"] is None and spot["value_source"] is None

    monkeypatch.setattr(config, "MAX_BORROW_METERS", 60)
    assert client.get(f"/api/spots/{MART}").json()["value_source"]["sensor_id"] == 1


def test_stale_neighbors_are_not_borrowed(client, monkeypatch):
    drop_readings(3)
    monkeypatch.setattr(config, "STALE_MINUTES", 1)  # 시드 측정값(2분 전)은 모두 오래된 값
    spot = client.get(f"/api/spots/{MART}").json()
    assert spot["latest_reading"] is None  # 오래된 이웃 값으로 현재 온도를 대신하지 않는다


def test_fresh_neighbor_beats_a_stale_on_site_sensor(client, monkeypatch):
    monkeypatch.setattr(config, "STALE_MINUTES", 1)  # 센서 3 의 시드 값은 오래된 값
    client.post("/api/readings", json=reading(sensor_id=1, measured_at=int(time.time()) - 10, temperature=19.5))
    spot = client.get(f"/api/spots/{MART}").json()
    assert spot["value_source"]["sensor_id"] == 1
    assert spot["value_source"]["is_on_site"] is False
    assert spot["latest_reading"]["temperature"] == 19.5
    assert spot["latest_reading"]["is_stale"] is False


def test_on_site_sensor_is_kept_when_it_is_fresh(client):
    spot = client.get(f"/api/spots/{MART}").json()  # 센서 3 시드 값은 아직 최신
    assert spot["value_source"]["sensor_id"] == 3
    assert spot["value_source"]["is_on_site"] is True


def test_lab_sensor_can_be_borrowed(client):
    drop_readings(4)
    drop_readings(5)
    # 테니스장(센서 4, 5 의 중간) 바로 북쪽 약 55m 에 연구실 센서 18 이 있다
    spots = {s["id"]: s for s in client.get("/api/spots").json()}
    lat, lon = spots[TENNIS]["latitude"], spots[TENNIS]["longitude"]
    add_lab_sensor(18, lat=lat + 0.0005, lon=lon, temperature=17)

    source = client.get(f"/api/spots/{TENNIS}").json()["value_source"]
    assert source["sensor_id"] == 118
    assert (source["ble_name"], source["lab_name"]) == (None, "sensor 18")
    assert source["location"] == "연구실 센서 18번"
    assert source["is_on_site"] is False
    assert 50 <= source["distance_m"] <= 60


def test_spot_without_coordinates_cannot_borrow(client):
    drop_readings(2)  # 센서 없는 샘플 장소(5)가 빌려 쓰도록 지정된 센서 2 의 값을 없앤다
    spot = client.get("/api/spots/5").json()
    assert spot["latest_reading"] is None  # 좌표가 없어서 다른 센서를 찾지 못한다


def test_borrowed_neighbors_exclude_the_spot_own_linked_sensors(client):
    drop_readings(4)
    drop_readings(5)
    # 테니스장은 센서 4, 5 가 연결되어 있다. 자기 센서를 이웃으로 또 빌리지 않고 다른 센서를 찾는다
    source = client.get(f"/api/spots/{TENNIS}").json()["value_source"]
    assert source["sensor_id"] not in (4, 5)
    assert source["is_on_site"] is False
