"""앱에 넣어 둘 기본 코스 목록을 서버 응답에서 그대로 뽑는다. (#37)

서버나 인터넷이 안 되는 곳에서도 앱이 센서를 감지했을 때 어느 장소인지 알 수 있어야 한다.
그래서 앱은 코스 목록을 이 순서로 쓴다: 서버, 폰에 저장해 둔 마지막 목록, 앱에 넣어 둔 기본 목록.
이 명령은 그 기본 목록을 만든다. 손으로 옮겨 적다가 서버와 어긋나는 일을 막으려고
서버가 GET /api/courses 로 주는 것과 같은 코드로 만든다.

사용:
    python -m app.export_tour                  # docs/tour_default.json 에 쓴다
    python -m app.export_tour 파일경로.json    # 다른 파일에 쓴다
    python -m app.export_tour -                # 화면에 출력한다

- 장소와 코스는 app/tour_data.py 의 실제 투어 데이터를 쓴다. 서버가 쓰는 DB 는 건드리지 않는다.
- 온도는 비워서 뽑는다. (latest_reading, recent_average, value_source 가 null)
  기본 목록에 예전 온도가 들어 있으면 서버가 안 될 때 그 온도가 현재 값처럼 보일 수 있어서이다.
- 장소나 코스를 고치면 다시 뽑아서 앱에 넣어야 한다. (tests/test_export_tour.py 가 어긋나면 알려 준다)
"""
import json
import sys
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from . import main, seed, tour_data
from .database import Base

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "docs" / "tour_default.json"


def build_snapshot() -> list[dict]:
    """서버의 /api/courses 응답과 같은 모양의 목록. 서버 DB 와 따로 만든 메모리 DB 에서 뽑는다."""
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    # 센서 값을 넣지 않으므로 온도 칸(latest_reading 등)은 모두 비어 있다
    seed.seed_if_empty(
        sample_readings=False,
        spots=tour_data.SPOTS,
        courses=tour_data.COURSES,
        card_spots=tour_data.CARD_SPOTS,
        session_factory=Session,
    )
    with Session() as db:
        courses = main.list_courses(db)
    return [course.model_dump(mode="json") for course in courses]


def to_json(snapshot: list[dict]) -> str:
    """한글이 그대로 보이는 JSON 글자. 끝에 줄바꿈을 붙인다."""
    return json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n"


def main_cli(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    text = to_json(build_snapshot())
    target = argv[0] if argv else str(DEFAULT_PATH)
    if target == "-":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except (AttributeError, OSError):
            pass
        sys.stdout.write(text)
        return 0
    path = Path(target)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"기본 코스 목록을 {path} 에 썼습니다. (코스 {len(json.loads(text))}개)")
    return 0


if __name__ == "__main__":
    sys.exit(main_cli())
