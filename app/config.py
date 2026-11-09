"""환경변수로 조정하는 설정값. 모듈 속성을 호출 시점에 읽으므로 테스트에서 바꿀 수 있다."""
import os


def _int(name: str, default: int, minimum: int = 1) -> int:
    try:
        return max(minimum, int(os.getenv(name, default)))
    except ValueError:
        return default


# 측정 후 이 시간(분) 이상 지나면 오래된 값(is_stale)으로 표시
STALE_MINUTES = _int("STALE_MINUTES", 30)

# 같은 센서의 최근 N개 측정값이 모두 같으면 고정값(is_suspect)으로 표시
FIXED_VALUE_RUN = _int("FIXED_VALUE_RUN", 5, minimum=2)

# 최근 평균을 계산하는 시간 범위(분)
AVG_WINDOW_MINUTES = _int("AVG_WINDOW_MINUTES", 30)
