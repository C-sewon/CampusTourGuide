"""측정값 전처리. DB에 의존하지 않는 순수 함수만 둔다."""
from collections.abc import Sequence

from .models import Reading


def age_minutes(measured_at: int, now: int) -> int:
    """측정 후 경과 시간(분). 센서 시계가 빨라 미래 시각이면 0."""
    return max(0, (now - measured_at) // 60)


def is_stale(age: int, stale_minutes: int) -> bool:
    return age >= stale_minutes


def is_fixed_run(count: int, span_seconds: int, min_count: int, min_minutes: int) -> bool:
    """같은 값이 이어진 구간이 개수와 시간 조건을 모두 채우면 True (센서가 멈춘 것으로 의심).

    count: 최신 측정값과 온도, 습도가 같은 연속 측정값 개수 (최신 값 포함)
    span_seconds: 그 구간의 첫 측정부터 최신 측정까지의 시간(초)
    """
    return count >= min_count and span_seconds >= min_minutes * 60


def sensor_status(has_data: bool, is_stale: bool, is_suspect: bool) -> str:
    """센서 상태. no_data > stale > suspect > ok 순으로 우선한다.

    값이 오래됐다면(stale) 새 값이 안 오는 것이 더 근본적인 문제이므로 suspect 보다 먼저 본다.
    """
    if not has_data:
        return "no_data"
    if is_stale:
        return "stale"
    if is_suspect:
        return "suspect"
    return "ok"


def average(readings: Sequence[Reading]) -> tuple[float, float, int] | None:
    """(평균 온도, 평균 습도, 개수). 측정값이 없으면 None."""
    if not readings:
        return None
    n = len(readings)
    temperature = sum(r.temperature for r in readings) / n
    humidity = sum(r.humidity for r in readings) / n
    return round(temperature, 2), round(humidity, 2), n
