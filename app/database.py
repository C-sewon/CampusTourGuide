import os

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./campus_tour.db")

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {},
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def ensure_current_schema(bind=None) -> None:
    """예전 구조의 표가 남아 있으면 알아보기 쉬운 오류로 멈춘다.

    create_all 은 이미 있는 표의 칸을 바꾸지 못해서, 구조가 바뀐 뒤 예전 DB를 그대로 쓰면
    저장할 때 SQL 오류가 난다. 개발용 SQLite 파일은 지우고 다시 만들면 된다.
    """
    from sqlalchemy import inspect

    inspector = inspect(bind or engine)
    problems = []
    if inspector.has_table("readings"):
        columns = {c["name"] for c in inspector.get_columns("readings")}
        if "seq" in columns or not {"rssi", "source"} <= columns:
            problems.append("readings")
    if inspector.has_table("spots"):
        columns = {c["name"] for c in inspector.get_columns("spots")}
        if "sensor_id" in columns or "has_card" not in columns:  # 센서를 하나만 쓰던 구조, 또는 카드 표시가 생기기 전 구조
            problems.append("spots")
    if inspector.has_table("sensors"):
        columns = {c["name"] for c in inspector.get_columns("sensors")}
        if not {"lab_name", "kind"} <= columns:
            problems.append("sensors")
    if inspector.has_table("courses"):
        columns = {c["name"] for c in inspector.get_columns("courses")}
        if "is_core" not in columns:  # 핵심 코스 표시가 생기기 전 구조
            problems.append("courses")
    if problems:
        raise RuntimeError(
            f"DB의 {', '.join(problems)} 표가 예전 구조입니다. "
            "개발용 SQLite 파일(campus_tour.db)을 지우고 서버를 다시 실행하세요."
        )
