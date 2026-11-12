from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReadingIn(BaseModel):
    """앱이 올리는 측정값. BLE 바이너리(x100 정수)는 앱에서 풀어 실수로 보낸다.

    센서는 mac 으로 찾고, mac 이 없으면(iOS) ble_name 으로 찾는다.
    """

    ble_name: str = Field(min_length=1, max_length=50, description="BLE 광고 이름 (예: opensrc1)")
    mac: str | None = Field(
        default=None,
        pattern=r"^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$",
        description="센서 MAC 주소. iOS 는 알 수 없어 생략할 수 있다",
    )
    measured_at: int = Field(ge=0, description="패킷의 측정 시각 (Unix 초)")
    temperature: float = Field(description="℃. 물리적 범위(-40~85) 밖이면 저장은 하되 오류값으로 제외")
    humidity: float = Field(description="%. 범위(0~100) 밖이면 저장은 하되 오류값으로 제외")
    aqi: int | None = Field(default=None, ge=0, le=255)
    tvoc: int | None = Field(default=None, ge=0, le=65535, description="ppb")
    eco2: int | None = Field(default=None, ge=0, le=65535, description="ppm")
    rssi: int | None = Field(default=None, ge=-127, le=20, description="dBm")

    @field_validator("mac")
    @classmethod
    def normalize_mac(cls, v: str | None) -> str | None:
        return v.upper().replace("-", ":") if v else v


class ReadingOut(BaseModel):
    sensor_id: int
    measured_at: int
    temperature: float
    humidity: float
    age_minutes: int = Field(description="측정 후 경과 시간(분). 'n분 전 측정' 표시용")
    is_stale: bool = Field(description="age_minutes 가 기준(STALE_MINUTES) 이상이면 true")
    is_suspect: bool = Field(
        description="같은 센서의 최근 측정값이 계속 같으면 true (센서 고장 의심). 값은 그대로 저장됨"
    )


class AverageOut(BaseModel):
    temperature: float
    humidity: float
    count: int = Field(description="평균에 쓰인 측정값 개수")
    window_minutes: int = Field(description="평균을 낸 시간 범위(분)")


class SensorOut(BaseModel):
    sensor_id: int = Field(description="센서 번호. BLE 이름 opensrcN 의 N")
    ble_name: str
    mac: str
    location: str = Field(description="설치 위치")
    latitude: float | None
    longitude: float | None


class SensorStatusOut(BaseModel):
    sensor_id: int
    ble_name: str | None = Field(description="등록된 센서의 BLE 이름. 등록되지 않은 센서면 null")
    mac: str | None
    location: str | None
    spot_names: list[str] = Field(description="이 센서를 쓰는 장소 이름. 연결된 장소가 없으면 빈 목록")
    status: str = Field(
        description="ok | stale(오래됨) | suspect(고정값 의심) | invalid(오류값만 옴) | no_data(측정값 없음)"
    )
    latest_reading: ReadingOut | None
    last_received_at: int | None = Field(
        description="서버가 이 센서의 값을 마지막으로 받은 시각(Unix 초). measured_at 과 크게 다르면 Pi 시계 의심"
    )


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
    recent_average: AverageOut | None = Field(
        description="최근 평균. 측정값이 없거나 is_suspect 이면 null"
    )


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
