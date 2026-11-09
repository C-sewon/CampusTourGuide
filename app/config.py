"""환경변수로 조정하는 설정값. 모듈 속성을 호출 시점에 읽으므로 테스트에서 바꿀 수 있다."""
import os


def _int(name: str, default: int, minimum: int = 1) -> int:
    try:
        return max(minimum, int(os.getenv(name, default)))
    except ValueError:
        return default


# 측정 후 이 시간(분) 이상 지나면 오래된 값(is_stale)으로 표시
STALE_MINUTES = _int("STALE_MINUTES", 30)

# 가장 최근 측정값과 온도, 습도가 같은 값이 연속으로 FIXED_VALUE_MIN_COUNT개 이상이고
# 그 구간이 FIXED_VALUE_MINUTES분 이상 이어지면 고정값(is_suspect)으로 표시.
# 센서가 1초 간격으로 광고하면 몇 초 동안 같은 값은 정상이므로 시간 조건을 함께 본다.
FIXED_VALUE_MIN_COUNT = _int("FIXED_VALUE_MIN_COUNT", 5, minimum=2)
FIXED_VALUE_MINUTES = _int("FIXED_VALUE_MINUTES", 10, minimum=0)

# 최근 평균을 계산하는 시간 범위(분)
AVG_WINDOW_MINUTES = _int("AVG_WINDOW_MINUTES", 30)
