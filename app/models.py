from sqlalchemy import Float, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


class Spot(Base):
    """캠퍼스 장소. sensor_id 로 해당 장소의 Pi 와 연결된다."""

    __tablename__ = "spots"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    name_en: Mapped[str | None] = mapped_column(String(100))
    description: Mapped[str] = mapped_column(Text, default="")
    description_en: Mapped[str | None] = mapped_column(Text)
    sensor_id: Mapped[int | None] = mapped_column(Integer, unique=True)  # BLE 센서ID (1바이트)
    ble_name: Mapped[str | None] = mapped_column(String(50))  # Pi 광고 이름 (확정되면 채움)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)


class Reading(Base):
    """Pi 측정값. (sensor_id, seq) 로 중복 제거."""

    __tablename__ = "readings"
    __table_args__ = (UniqueConstraint("sensor_id", "seq", name="uq_reading_sensor_seq"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    sensor_id: Mapped[int] = mapped_column(Integer, index=True)
    seq: Mapped[int] = mapped_column(Integer)
    measured_at: Mapped[int] = mapped_column(Integer, index=True)  # Unix 초
    temperature: Mapped[float] = mapped_column(Float)  # ℃
    humidity: Mapped[float] = mapped_column(Float)  # %
    received_at: Mapped[int] = mapped_column(Integer)  # 서버 수신 시각 (Unix 초)


class Course(Base):
    __tablename__ = "courses"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    name_en: Mapped[str | None] = mapped_column(String(100))
    description: Mapped[str] = mapped_column(Text, default="")

    stops: Mapped[list["CourseSpot"]] = relationship(
        back_populates="course", order_by="CourseSpot.position", cascade="all, delete-orphan"
    )


class CourseSpot(Base):
    __tablename__ = "course_spots"
    __table_args__ = (UniqueConstraint("course_id", "position", name="uq_course_position"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("courses.id"))
    spot_id: Mapped[int] = mapped_column(ForeignKey("spots.id"))
    position: Mapped[int] = mapped_column(Integer)  # 코스 내 순서 (1부터)
    minutes: Mapped[int] = mapped_column(Integer)  # 이전 지점에서 이동 + 이 지점 관람 시간(분)

    course: Mapped[Course] = relationship(back_populates="stops")
    spot: Mapped[Spot] = relationship()
