from app import processing
from app.models import Reading


def r(temp=20.0, hum=50.0):
    return Reading(sensor_id=1, seq=0, measured_at=0, temperature=temp, humidity=hum, received_at=0)


def test_age_minutes_floors_and_clamps_future():
    assert processing.age_minutes(1000, 1000 + 119) == 1
    assert processing.age_minutes(1000, 1000 + 120) == 2
    assert processing.age_minutes(2000, 1000) == 0  # 센서 시계가 빠른 경우


def test_is_valid_reading_rejects_only_all_zero():
    assert not processing.is_valid_reading(0.0, 0.0)
    # 겨울에는 온도 0.0도가 실제로 나올 수 있어서 습도가 있으면 정상이다
    assert processing.is_valid_reading(0.0, 55.0)
    assert processing.is_valid_reading(-3.2, 0.0)
    assert processing.is_valid_reading(21.0, 51.0)


def test_is_stale_boundary():
    assert not processing.is_stale(29, 30)
    assert processing.is_stale(30, 30)


def test_fixed_run_needs_enough_samples():
    # 10분 이상 이어져도 측정값이 너무 적으면 폰이 가끔 올린 값일 수 있어 의심하지 않는다
    assert not processing.is_fixed_run(count=4, span_seconds=3600, min_count=5, min_minutes=10)
    assert processing.is_fixed_run(count=5, span_seconds=3600, min_count=5, min_minutes=10)


def test_fixed_run_needs_enough_time():
    # 1초 간격 센서는 몇 초 동안 같은 값이 나올 수 있다 (실제 센서 출력에서 확인됨)
    assert not processing.is_fixed_run(count=50, span_seconds=49, min_count=5, min_minutes=10)
    assert not processing.is_fixed_run(count=50, span_seconds=599, min_count=5, min_minutes=10)
    assert processing.is_fixed_run(count=50, span_seconds=600, min_count=5, min_minutes=10)


def test_fixed_run_with_zero_minutes_uses_count_only():
    assert processing.is_fixed_run(count=5, span_seconds=0, min_count=5, min_minutes=0)


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
