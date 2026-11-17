"""`.env.example` 이 실제 설정과 어긋나지 않는지 확인한다. (설정 이름이 바뀌거나 기본값이 달라지면 실패)"""
import pathlib

from app import config, database

ENV_EXAMPLE = pathlib.Path(__file__).resolve().parent.parent / ".env.example"


def read_example():
    values = {}
    for line in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            values[key] = value
    return values


def test_every_example_key_is_a_real_setting_with_the_default_value():
    values = read_example()
    assert values, ".env.example 에 설정이 없다"
    for key, value in values.items():
        if key == "DATABASE_URL":
            actual = database.DATABASE_URL
        else:
            assert hasattr(config, key), f"{key} 는 app/config.py 에 없는 설정이다"
            actual = getattr(config, key)
        if isinstance(actual, bool):
            actual = "1" if actual else "0"
        assert str(actual) == value, f"{key}: 예시 값 {value} 와 기본값 {actual} 이 다르다"


def test_every_documented_setting_is_in_the_example():
    values = read_example()
    for key in (
        "LAB_SYNC_ENABLED", "LAB_SYNC_URL", "LAB_SYNC_SECONDS", "LAB_SYNC_TIMEOUT",
        "STALE_MINUTES", "FIXED_VALUE_MIN_COUNT", "FIXED_VALUE_MINUTES", "AVG_WINDOW_MINUTES",
        "MAX_BORROW_METERS",
    ):
        assert key in values, f"{key} 가 .env.example 에 없다"
