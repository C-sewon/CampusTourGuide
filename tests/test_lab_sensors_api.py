"""연구실 센서를 센서 목록과 상태 API 에서 어떻게 보여주는가."""
import time

from app import lab_sync, seed
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)


def add_lab_sensor(number, lat=36.6275, lon=127.4532, temperature=20, seconds_ago=30, fresh=True):
    """연구실 API 응답을 흉내 내어 연구실 센서를 등록하고 측정값을 저장한다."""
    now = int(time.time())
    row = {
        "sensor": f"sensor {number:02d}", "latitude": lat, "longitude": lon,
        "temperature": temperature, "co2": 500, "fresh": fresh, "mode": "direct",
    }
    from tests.test_lab_sync import lab_time

    row["time"] = lab_time(now - seconds_ago)
    with seed.SessionLocal() as db:
        lab_sync.sync_rows(db, [row], now=now)


def test_sensor_list_includes_lab_sensors_with_kind(client):
    add_lab_sensor(18)
    sensors = client.get("/api/sensors").json()
    assert [s["sensor_id"] for s in sensors] == [1, 2, 3, 4, 5, 118]
    lab = sensors[-1]
    assert lab["kind"] == "lab"
    assert (lab["ble_name"], lab["mac"], lab["lab_name"]) == (None, None, "sensor 18")
    assert lab["location"] == "연구실 센서 18번"
    assert (lab["latitude"], lab["longitude"]) == (36.6275, 127.4532)
    assert sensors[0]["kind"] == "opensrc"


def test_sensor_list_can_be_filtered_by_kind(client):
    add_lab_sensor(18)
    add_lab_sensor(20)
    assert [s["sensor_id"] for s in client.get("/api/sensors?kind=lab").json()] == [118, 120]
    assert [s["sensor_id"] for s in client.get("/api/sensors?kind=opensrc").json()] == [1, 2, 3, 4, 5]


def test_sensor_status_shows_kind_and_can_be_filtered(client):
    add_lab_sensor(18)
    status = client.get("/api/sensors/status").json()
    by_id = {s["sensor_id"]: s for s in status}
    assert by_id[118]["kind"] == "lab"
    assert by_id[118]["status"] == "ok"
    assert by_id[118]["latest_reading"]["temperature"] == 20.0
    assert by_id[1]["kind"] == "opensrc"

    lab_only = client.get("/api/sensors/status?kind=lab").json()
    assert [s["sensor_id"] for s in lab_only] == [118]
    opensrc_only = client.get("/api/sensors/status?kind=opensrc").json()
    assert [s["sensor_id"] for s in opensrc_only] == [1, 2, 3, 4, 5]


def test_lab_sensor_that_is_not_fresh_is_registered_but_has_no_data(client):
    add_lab_sensor(20, fresh=False)
    by_id = {s["sensor_id"]: s for s in client.get("/api/sensors/status?kind=lab").json()}
    assert by_id[120]["status"] == "no_data"
