# CampusTourGuide

캠퍼스 BLE 투어 가이드 — 백엔드 (FastAPI + SQLite)

## 실행

```bash
py -m venv .venv
.venv\Scripts\activate          # Windows
pip install -r requirements.txt
uvicorn app.main:app --reload
```

- API 문서(Swagger): http://127.0.0.1:8000/docs
- 최초 실행 시 `campus_tour.db`가 생성되고 더미 데이터(장소 5곳, 코스 2개, 샘플 측정값)가 들어갑니다.
- DB 변경: 환경변수 `DATABASE_URL` (기본 `sqlite:///./campus_tour.db`)

## 테스트

```bash
pytest
```

## API (1주차 인터페이스)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/readings` | 측정값 업로드. `(sensor_id, seq)` 중복 시 200 `duplicate`, 신규는 201 `created` |
| GET | `/api/spots` | 장소 목록 + 최신 환경값 |
| GET | `/api/spots/{id}` | 장소 상세 + 최신 환경값 |
| GET | `/api/courses` | 코스 목록, 장소 순서, 총 소요시간(`total_minutes`) |
| GET | `/health` | 헬스체크 |

### POST /api/readings 본문

```json
{ "sensor_id": 1, "seq": 100, "measured_at": 1731000000, "temperature": 23.5, "humidity": 48.2 }
```

- BLE의 x100 정수는 앱에서 풀어 실수(℃, %)로 보냅니다.
- `measured_at`은 Unix 초. 최신값 응답의 `age_minutes`로 "n분 전 측정"을 표시합니다.

## 확정 필요 항목

- Pi 5대의 센서ID ↔ 설치 장소 매핑 (현재 `app/seed.py`는 임의 매핑)
- 측정 시각 기준(Unix 초 가정)
