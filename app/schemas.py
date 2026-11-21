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
    temperature: float = Field(description="℃. 현실적인 범위(-20~45) 밖이면 저장은 하되 오류값으로 제외")
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
    humidity: float | None = Field(description="연구실 API 로 가져온 값에는 습도가 없어 null")
    age_minutes: int = Field(description="측정 후 경과 시간(분). 'n분 전 측정' 표시용")
    is_stale: bool = Field(description="age_minutes 가 기준(STALE_MINUTES) 이상이면 true")
    is_suspect: bool = Field(
        description="같은 센서의 최근 측정값이 계속 같으면 true (센서 고장 의심). 값은 그대로 저장됨"
    )


class AverageOut(BaseModel):
    temperature: float
    humidity: float | None = Field(description="습도 값이 있는 측정값이 없으면 null")
    count: int = Field(description="평균에 쓰인 측정값 개수")
    window_minutes: int = Field(description="평균을 낸 시간 범위(분)")


class SensorOut(BaseModel):
    sensor_id: int = Field(description="센서 번호. opensrc 센서는 BLE 이름 opensrcN 의 N, 연구실 센서는 100 + 연구실 번호")
    kind: str = Field(description="opensrc: 우리 팀 오픈소스 센서, lab: 연구실 센서")
    ble_name: str | None = Field(description="연구실 센서는 null")
    mac: str | None = Field(description="연구실 센서는 null")
    location: str = Field(description="설치 위치")
    lab_name: str | None = Field(description="연구실 공용 API 의 센서 이름 (예: sensor 07)")
    latitude: float | None
    longitude: float | None


class SensorStatusOut(BaseModel):
    sensor_id: int
    kind: str | None = Field(description="opensrc 또는 lab. 등록되지 않은 센서면 null")
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


class SpotSensorOut(BaseModel):
    sensor_id: int
    ble_name: str
    mac: str
    location: str


class ArrivalOut(BaseModel):
    method: str = Field(
        description="ble_auto: 장소에 설치된 센서가 있어 BLE 로 도착을 자동 판정(수동 버튼은 보조). "
        "manual: 센서가 없어 방문객이 도착 버튼으로 체크"
    )
    sensors: list[SpotSensorOut] = Field(
        description="도착 판정에 쓰는, 이 장소에 설치된 센서. 앱이 스캔한 센서를 이 목록과 비교한다. manual 이면 빈 목록"
    )


class ValueSourceOut(BaseModel):
    sensor_id: int
    ble_name: str | None = Field(description="연구실 센서는 null")
    lab_name: str | None = Field(description="연구실 API 의 센서 이름 (예: sensor 18)")
    location: str = Field(description="값을 낸 센서의 설치 위치. 화면에 OO 센서 기준으로 표시할 때 쓴다")
    is_on_site: bool = Field(
        description="true 면 이 장소에 설치된 센서의 값, false 면 가까운 센서 값을 빌려 쓴 것"
    )
    distance_m: int | None = Field(
        description="장소와 센서 사이의 거리(m). 장소나 센서의 좌표를 모르면 null. 약 56m 떨어진 센서 기준처럼 표시할 수 있다"
    )


class SpotOut(BaseModel):
    id: int
    name: str
    name_en: str | None
    description: str
    description_en: str | None
    latitude: float | None
    longitude: float | None
    arrival: ArrivalOut
    latest_reading: ReadingOut | None
    recent_average: AverageOut | None = Field(
        description="최근 평균. 측정값이 없거나 is_suspect 이면 null"
    )
    value_source: ValueSourceOut | None = Field(
        description="latest_reading 이 어느 센서에서 왔는지. 측정값이 없으면 null"
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
    is_core: bool = Field(
        description="핵심 코스 여부. 더울 때나 추울 때 추천하는 가장 짧은 코스이고, 앱의 핵심 코스 카드가 이 코스의 장소를 쓴다"
    )
    total_minutes: int
    spots: list[CourseSpotOut]


class LabSyncResultOut(BaseModel):
    fetched: int = Field(description="연구실 API 가 준 항목 수")
    new_sensors: int = Field(description="새로 등록한 연구실 센서")
    unknown_sensor: int = Field(description="센서 이름 형식이 다르거나 위치가 없어 건너뜀")
    not_fresh: int = Field(description="1시간 이내 갱신이 아니라 건너뜀")
    invalid: int = Field(description="시각이나 온도를 읽을 수 없어 건너뜀")
    duplicate: int = Field(description="이미 저장된 값")
    stored: int = Field(description="새로 저장")


class LabSyncStatusOut(BaseModel):
    enabled: bool
    interval_seconds: int
    url: str
    last_attempt: int | None = Field(description="마지막으로 시도한 시각(Unix 초)")
    last_success: int | None = Field(description="마지막으로 성공한 시각(Unix 초)")
    last_error: str | None = Field(description="마지막 시도가 실패했다면 원인")
    last_result: LabSyncResultOut | None
