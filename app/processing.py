"""측정값 전처리. DB에 의존하지 않는 순수 함수만 둔다."""
import math
from collections.abc import Sequence

from .models import Reading


# 캠퍼스 야외에서 나올 수 있는 현실적인 범위. 벗어나면 오류값으로 본다.
# 센서 칩이 낼 수 있는 값(-40~85℃)은 더 넓어서, 고장 난 센서가 내는 73℃ 같은 값이 걸러지지 않았다.
TEMPERATURE_RANGE = (-20.0, 45.0)  # ℃
HUMIDITY_RANGE = (0.0, 100.0)  # %


def is_valid_reading(temperature: float, humidity: float | None) -> bool:
    """오류값이 아니면 True. 아래 경우는 센서 오류값으로 본다.

    - 온도나 습도가 물리적 범위를 벗어남
    - 온도와 습도가 모두 0.0 (연결 불량 등으로 0만 올라오는 경우, #9 에서 센서 3, 4 확인)

    온도 0.0도는 겨울에 실제로 나올 수 있어서, 습도까지 함께 0일 때만 무효로 본다.
    습도가 없는 값(연구실 API)은 온도만 본다. NaN 은 범위 비교가 거짓이 되어 무효로 처리된다.
    """
    if not (TEMPERATURE_RANGE[0] <= temperature <= TEMPERATURE_RANGE[1]):
        return False
    if humidity is None:
        return True
    if not (HUMIDITY_RANGE[0] <= humidity <= HUMIDITY_RANGE[1]):
        return False
    return not (temperature == 0.0 and humidity == 0.0)


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """두 지점 사이의 거리(m). 지구를 구로 보고 계산한다(하버사인). 캠퍼스 규모에서는 오차가 작다."""
    earth_radius = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = (
        math.sin((p2 - p1) / 2) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    )
    return 2 * earth_radius * math.asin(math.sqrt(a))


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


def sensor_status(
    has_data: bool, is_stale: bool, is_suspect: bool, only_invalid: bool = False
) -> str:
    """센서 상태. no_data > invalid > stale > suspect > ok 순으로 우선한다.

    has_data: 정상 측정값이 하나라도 있는가
    only_invalid: 측정값은 오지만 전부 오류값(온도와 습도가 모두 0.0)인가
    값이 오래됐다면(stale) 새 값이 안 오는 것이 더 근본적인 문제이므로 suspect 보다 먼저 본다.
    """
    if only_invalid:
        return "invalid"
    if not has_data:
        return "no_data"
    if is_stale:
        return "stale"
    if is_suspect:
        return "suspect"
    return "ok"


def average(readings: Sequence[Reading]) -> tuple[float, float | None, int] | None:
    """(평균 온도, 평균 습도, 개수). 측정값이 없으면 None.

    습도는 값이 있는 측정값만으로 평균을 내고, 하나도 없으면 None 이다.
    """
    if not readings:
        return None
    n = len(readings)
    temperature = sum(r.temperature for r in readings) / n
    humidities = [r.humidity for r in readings if r.humidity is not None]
    humidity = round(sum(humidities) / len(humidities), 2) if humidities else None
    return round(temperature, 2), humidity, n
