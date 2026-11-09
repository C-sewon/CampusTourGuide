from pydantic import BaseModel, ConfigDict, Field


class ReadingIn(BaseModel):
    """앱이 올리는 측정값. BLE 바이너리(x100 정수)는 앱에서 풀어 실수로 보낸다."""

    sensor_id: int = Field(ge=0, le=255)
    seq: int = Field(ge=0, le=2**32 - 1)
    measured_at: int = Field(ge=0, description="측정 시각 (Unix 초)")
    temperature: float = Field(ge=-40, le=85, description="℃")
    humidity: float = Field(ge=0, le=100, description="%")


class ReadingOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    sensor_id: int
    seq: int
    measured_at: int
    temperature: float
    humidity: float
    age_minutes: int = Field(description="측정 후 경과 시간(분). 'n분 전 측정' 표시용")


class ReadingResult(BaseModel):
    status: str  # "created" | "duplicate"


class SpotOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    name_en: str | None
    description: str
    description_en: str | None
    sensor_id: int | None
    ble_name: str | None
    latitude: float | None
    longitude: float | None
    latest_reading: ReadingOut | None


class CourseSpotOut(BaseModel):
    position: int
    minutes: int
    spot: SpotOut


class CourseOut(BaseModel):
    id: int
    name: str
    name_en: str | None
    description: str
    total_minutes: int
    spots: list[CourseSpotOut]
