from app import processing
from app.models import Reading


def r(temp=20.0, hum=50.0):
    return Reading(sensor_id=1, seq=0, measured_at=0, temperature=temp, humidity=hum, received_at=0)


def test_age_minutes_floors_and_clamps_future():
    assert processing.age_minutes(1000, 1000 + 119) == 1
    assert processing.age_minutes(1000, 1000 + 120) == 2
    assert processing.age_minutes(2000, 1000) == 0  # 센서 시계가 빠른 경우


def test_is_stale_boundary():
    assert not processing.is_stale(29, 30)
    assert processing.is_stale(30, 30)


def test_fixed_run_needs_enough_samples():
    assert not processing.is_fixed_run([r()] * 4, 5)
    assert processing.is_fixed_run([r()] * 5, 5)


def test_fixed_run_broken_by_any_change():
    assert not processing.is_fixed_run([r(), r(), r(temp=20.1), r(), r()], 5)
    assert not processing.is_fixed_run([r(), r(), r(hum=50.1), r(), r()], 5)


def test_fixed_run_only_looks_at_newest():
    # 앞(최신) 5개만 본다. 더 오래된 값이 달라도 상관없음
    assert processing.is_fixed_run([r()] * 5 + [r(temp=30.0)], 5)


def test_sensor_status_priority():
    assert processing.sensor_status(False, False, False) == "no_data"
    assert processing.sensor_status(True, False, False) == "ok"
    assert processing.sensor_status(True, False, True) == "suspect"
    assert processing.sensor_status(True, True, False) == "stale"
    # 오래된 데이터가 고정값이기도 하면 stale 을 우선한다
    assert processing.sensor_status(True, True, True) == "stale"


def test_average():
    assert processing.average([]) is None
    assert processing.average([r(20, 40), r(25, 60)]) == (22.5, 50.0, 2)
