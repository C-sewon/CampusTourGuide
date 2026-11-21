from sqlalchemy import Boolean, Float, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


class Sensor(Base):
    """캠퍼스에 설치된 센서.

    kind 가 opensrc 면 우리 팀이 쓰는 오픈소스 센서이다. 센서 N 은 BLE 이름이 opensrcN 인 센서이고 id 도 N 이다.
    kind 가 lab 이면 연구실 센서이다. 연구실 API 에서 자동 등록하며 id 는 100 + 연구실 번호이다.
    (sensor 18 은 id 118). 연구실 센서는 MAC 과 BLE 이름이 없다.
    """

    __tablename__ = "sensors"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=False)
    kind: Mapped[str] = mapped_column(String(10), default="opensrc", server_default="opensrc")
    ble_name: Mapped[str | None] = mapped_column(String(50), unique=True)
    mac: Mapped[str | None] = mapped_column(String(17), unique=True)  # 대문자, 콜론 구분
    location: Mapped[str] = mapped_column(String(100))  # 설치 위치
    lab_name: Mapped[str | None] = mapped_column(String(20), unique=True)  # 연구실 API 의 센서 이름 (예: sensor 07)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)


class Spot(Base):
    """캠퍼스 장소. 장소 하나는 센서를 여러 개 쓸 수 있고(SpotSensor), 센서 하나를 여러 장소가 쓸 수도 있다."""

    __tablename__ = "spots"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    name_en: Mapped[str | None] = mapped_column(String(100))
    description: Mapped[str] = mapped_column(Text, default="")
    description_en: Mapped[str | None] = mapped_column(Text)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    # 이 장소에 도착하면 앱이 카드를 띄우는지(#37). 핵심 코스의 장소와 몇몇 대표 장소가 true 이다
    has_card: Mapped[bool] = mapped_column(Boolean, default=False)

    sensor_links: Mapped[list["SpotSensor"]] = relationship(
        order_by="SpotSensor.priority", cascade="all, delete-orphan"
    )


class SpotSensor(Base):
    """장소가 온도를 가져올 센서. priority 가 작을수록 먼저 쓴다.

    on_site 가 True 면 그 장소에 설치된 센서이고(BLE 로 도착을 자동 판정할 수 있다),
    False 면 센서가 없는 장소가 가까운 센서 값을 빌려 쓰는 것이다.
    """

    __tablename__ = "spot_sensors"
    __table_args__ = (UniqueConstraint("spot_id", "sensor_id", name="uq_spot_sensor"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("spots.id"))
    sensor_id: Mapped[int] = mapped_column(ForeignKey("sensors.id"))
    priority: Mapped[int] = mapped_column(Integer, default=1)
    on_site: Mapped[bool] = mapped_column(Boolean, default=True)

    sensor: Mapped[Sensor] = relationship()


class Reading(Base):
    """Pi 측정값. 같은 센서의 같은 측정 시각(sensor_id, measured_at)은 한 번만 저장한다."""

    __tablename__ = "readings"
    __table_args__ = (
        UniqueConstraint("sensor_id", "measured_at", name="uq_reading_sensor_measured_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    sensor_id: Mapped[int] = mapped_column(Integer, index=True)  # sensors.id
    measured_at: Mapped[int] = mapped_column(Integer, index=True)  # Unix 초 (Pi 시계)
    temperature: Mapped[float] = mapped_column(Float)  # ℃
    humidity: Mapped[float | None] = mapped_column(Float)  # %. 연구실 API 로 가져온 값에는 없음
    aqi: Mapped[int | None] = mapped_column(Integer)  # 공기질 지수 (화면에는 쓰지 않고 저장만)
    tvoc: Mapped[int | None] = mapped_column(Integer)  # ppb
    eco2: Mapped[int | None] = mapped_column(Integer)  # ppm
    rssi: Mapped[int | None] = mapped_column(Integer)  # 폰이 받은 신호 세기 (dBm)
    received_at: Mapped[int] = mapped_column(Integer)  # 서버 수신 시각 (Unix 초)
    # 값의 출처. ble: 앱이 BLE 로 읽어 올린 값, lab: 연구실 공용 API 에서 가져온 값(온도만, 정수)
    source: Mapped[str] = mapped_column(String(10), default="ble", server_default="ble")


class Course(Base):
    __tablename__ = "courses"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    name_en: Mapped[str | None] = mapped_column(String(100))
    description: Mapped[str] = mapped_column(Text, default="")
    # 핵심 코스 여부. 더울 때나 추울 때 추천하는 가장 짧은 코스이고, 자유 탐색에서 핵심 코스 카드(#37)에 쓴다
    is_core: Mapped[bool] = mapped_column(Boolean, default=False)

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
