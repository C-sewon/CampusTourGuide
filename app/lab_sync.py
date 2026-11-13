"""연구실 공용 API(/sensor/api/map)에서 센서 최신 온도를 가져와 readings 에 저장한다.

- 연구실 API 는 온도(정수, 소수점 버림)와 CO2 만 준다. 습도는 없으므로 humidity 는 비워 둔다.
- 1시간 이내에 갱신된 값(fresh)만 가져온다.
- 우리 DB 에 저장해 두므로 연구실 서버가 응답하지 않아도 마지막 값을 보여줄 수 있다.
- 지금은 sensors 테이블에 연구실 이름(lab_name)이 등록된 센서(오픈소스 5대)만 처리한다.
"""
import logging
import re
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone

import httpx
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import config, database, models

log = logging.getLogger("lab_sync")

KST = timezone(timedelta(hours=9))
_MONTHS = {
    m: i
    for i, m in enumerate(
        ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"], start=1
    )
}
_TIME_RE = re.compile(r"^\w{3}\s+(\w{3})\s+(\d{1,2})\s+(\d{2}):(\d{2}):(\d{2})\s+(\d{4})$")
_FUTURE_TOLERANCE = 300  # 센서 시계가 이만큼(초) 빠른 것까지는 허용


def parse_lab_time(text: str) -> int | None:
    """연구실 API 의 시각 문자열(예: Tue Nov 10 07:09:55 2026, KST)을 Unix 초로 바꾼다. 형식이 다르면 None."""
    m = _TIME_RE.match(str(text).strip())
    if not m or m.group(1) not in _MONTHS:
        return None
    try:
        dt = datetime(
            int(m.group(6)), _MONTHS[m.group(1)], int(m.group(2)),
            int(m.group(3)), int(m.group(4)), int(m.group(5)), tzinfo=KST,
        )
    except ValueError:
        return None
    return int(dt.timestamp())


def normalize_lab_name(name: str) -> str | None:
    """sensor1, sensor 1, sensor01 은 모두 sensor 01 로 맞춘다 (연구실 서버와 같은 규칙)."""
    m = re.fullmatch(r"\s*sensor\s*0*(\d+)\s*", str(name), flags=re.IGNORECASE)
    return f"sensor {int(m.group(1)):02d}" if m else None


@dataclass
class SyncResult:
    fetched: int = 0  # API 가 준 항목 수
    unknown_sensor: int = 0  # 등록되지 않은 센서라 건너뜀
    not_fresh: int = 0  # 1시간 이내 갱신이 아니라 건너뜀
    invalid: int = 0  # 시각이나 온도를 읽을 수 없어 건너뜀
    duplicate: int = 0  # 이미 저장된 값
    stored: int = 0  # 새로 저장


def fetch_rows(url: str | None = None, timeout: float | None = None) -> list[dict]:
    response = httpx.get(url or config.LAB_SYNC_URL, timeout=timeout or config.LAB_SYNC_TIMEOUT)
    response.raise_for_status()
    rows = response.json()
    if not isinstance(rows, list):
        raise ValueError("연구실 API 응답이 목록이 아닙니다")
    return rows


def sync_rows(db: Session, rows: list[dict], now: int | None = None) -> SyncResult:
    """API 항목들을 readings 에 저장한다. 같은 (센서, 측정 시각)은 한 번만 저장한다."""
    now = int(time.time()) if now is None else now
    result = SyncResult(fetched=len(rows))
    sensors = {s.lab_name: s for s in db.scalars(select(models.Sensor)) if s.lab_name}

    for row in rows:
        try:
            name = normalize_lab_name(row["sensor"])
            sensor = sensors.get(name) if name else None
            if sensor is None:
                result.unknown_sensor += 1
                continue

            if sensor.latitude is None and row.get("latitude") is not None:
                sensor.latitude, sensor.longitude = float(row["latitude"]), float(row["longitude"])

            if not row.get("fresh"):
                result.not_fresh += 1
                continue

            measured_at = parse_lab_time(row["time"])
            temperature = float(row["temperature"])
        except (KeyError, TypeError, ValueError):
            result.invalid += 1
            continue
        if measured_at is None or measured_at > now + _FUTURE_TOLERANCE:
            result.invalid += 1
            continue

        exists = db.scalars(
            select(models.Reading.id).where(
                models.Reading.sensor_id == sensor.id, models.Reading.measured_at == measured_at
            )
        ).first()
        if exists is not None:
            result.duplicate += 1
            continue
        try:
            with db.begin_nested():
                db.add(
                    models.Reading(
                        sensor_id=sensor.id,
                        measured_at=measured_at,
                        temperature=temperature,
                        humidity=None,
                        received_at=now,
                        source="lab",
                    )
                )
        except IntegrityError:  # 동시에 같은 값이 들어간 경우
            result.duplicate += 1
            continue
        result.stored += 1

    db.commit()
    return result


# 마지막 동기화 결과. GET /api/lab-sync/status 로 확인한다
state: dict = {"last_attempt": None, "last_success": None, "last_error": None, "last_result": None}


def run_once() -> None:
    """한 번 가져와서 저장한다. 실패해도 예외를 던지지 않고 state 에 기록한다."""
    state["last_attempt"] = int(time.time())
    try:
        rows = fetch_rows()
        with database.SessionLocal() as db:
            result = sync_rows(db, rows)
    except Exception as exc:  # 연구실 서버 장애 등으로 서비스가 멈추면 안 된다
        state["last_error"] = f"{type(exc).__name__}: {exc}"
        log.warning("연구실 센서 데이터를 가져오지 못했습니다: %s", state["last_error"])
        return
    state["last_success"] = int(time.time())
    state["last_error"] = None
    state["last_result"] = asdict(result)
    log.info("연구실 센서 데이터 동기화: %s", state["last_result"])
