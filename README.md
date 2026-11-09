# CampusTourGuide

캠퍼스 BLE 투어 가이드 (오픈소스개발프로젝트 팀 프로젝트)

캠퍼스에 설치된 라즈베리파이 센서의 BLE 신호로 방문객의 위치를 인식하고, 장소 소개와 실시간 온습도, 투어 코스를 안내하는 앱입니다.
이 저장소는 **백엔드(서버 및 DB)** 입니다.

## 프로젝트 소개

- **해결할 문제**: 방문객은 지도를 봐도 그 장소의 날씨를 모릅니다. 기상청 날씨는 캠퍼스 안의 장소별 차이를 반영하지 못하고, GPS는 건물이 밀집한 곳에서 오차가 큽니다.
- **핵심 기능**
  1. 위치 인식 장소 안내: Pi에 가까워지면 BLE로 장소를 인식하고 소개 카드를 표시
  2. 실시간 온습도: 장소에서 가장 가까운 Pi의 현재 온습도와 측정 시각을 카드에 표시
  3. 코스 및 소요 시간: 고정 코스 2~3개, 코스별 소요시간, 방문 자동 체크와 진행률
  4. 환경 맞춤 코스 추천: 너무 덥거나 추우면 짧은 코스 추천
  5. (선택) 지도 길안내, 영어 지원, 관리자 콘텐츠 편집
- 핵심 기능이 누적 데이터가 아니라 **현재 값**만으로 동작합니다. 서버 값은 오래됐을 수 있어 항상 "n분 전 측정"을 함께 표시합니다.

## 동작 구조

```
Pi(5대): 센서 측정 + BLE 광고
   ↓ BLE
앱: 스캔 → RSSI로 "도착" 판정 → 장소 카드 + 온습도 표시
   ↓ HTTP
서버: 측정값 저장, 장소·코스 정보 제공   ← 이 저장소
```

- BLE에서는 짧은 바이너리(약 13바이트: 센서ID 1 + 일련번호 4 + 시각 4 + 온도 2(x100) + 습도 2(x100)), 서버로는 JSON을 사용합니다.
- 장소는 캠퍼스 전체로 잡고, 장소의 온습도는 **가장 가까운 센서 값**을 사용하는 방향입니다. (센서가 없는 장소의 도착 판정 방식은 #2 에서 논의 중)

## 팀 구성

| 역할 | 담당 |
| --- | --- |
| Pi, 센서 | 유서진 |
| BLE, 네트워크 | 황지유 |
| 백엔드 (서버 및 DB) | 최세원(팀장) |
| 프론트엔드 (앱 UI 및 문서) | 홍유진 |

## 센서 (Pi 5대)

수업에서 제공된 센서 기준입니다. MAC 주소는 아직 확정되지 않았습니다 (TBA).

| 센서 | BLE 이름 | 설치 위치 |
| --- | --- | --- |
| 1 | `opensrc_team1` | 도서관 뒷편 (진로창업본부 입구) |
| 2 | `opensrc_team2` | 사회과학대 입구 |
| 3 | `opensrc_team3` | 사회과학대 옆 이마트24 건너편 |
| 4 | `opensrc_team4` | 테니스장 앞 |
| 5 | `opensrc_team5` | 테니스장 건너편 |

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

## API

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
- 앱이 재전송해도 안전하도록 같은 `(sensor_id, seq)`는 중복으로 처리합니다.
- 범위를 벗어난 값(온도 -40~85, 습도 0~100, 센서ID 0~255)은 422로 거절합니다.

### 장소 응답의 환경값 (`/api/spots`, `/api/spots/{id}`, `/api/courses`)

```json
{
  "latest_reading": {
    "sensor_id": 1, "seq": 100, "measured_at": 1731000000,
    "temperature": 23.5, "humidity": 48.2,
    "age_minutes": 3, "is_stale": false, "is_suspect": false
  },
  "recent_average": { "temperature": 23.1, "humidity": 48.9, "count": 6, "window_minutes": 30 }
}
```

- `is_stale`: 측정 후 `STALE_MINUTES`(기본 30분) 이상 지났으면 `true`. 센서 전원이 꺼진 경우에 "오래된 값"으로 표시하는 용도입니다.
- `is_suspect`: 같은 센서의 최근 `FIXED_VALUE_RUN`(기본 5)개 측정값의 온도와 습도가 모두 같으면 `true`. 센서가 멈춘 것으로 의심하는 표시이고, **값은 버리지 않고 그대로 저장**합니다. 이때 `recent_average`는 `null`입니다.
- `recent_average`: 최근 `AVG_WINDOW_MINUTES`(기본 30분) 안의 측정값 평균. 측정값이 없으면 `null`입니다.
- `latest_reading`, `recent_average`는 연결된 센서가 없거나 측정값이 없으면 `null`입니다.

### 설정 (환경변수)

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DATABASE_URL` | `sqlite:///./campus_tour.db` | DB 주소 |
| `STALE_MINUTES` | 30 | 오래된 값으로 보는 기준(분) |
| `FIXED_VALUE_RUN` | 5 | 고정값으로 의심하는 연속 개수 (최소 2) |
| `AVG_WINDOW_MINUTES` | 30 | 최근 평균의 시간 범위(분) |

## 일정

| 주차 | 기간 | 백엔드 |
| --- | --- | --- |
| 1 | 11/9~15 | DB·API 뼈대, 더미 데이터 |
| 2 | 11/16~22 | 코스 API, 소요 시간 |
| 3 | 11/23~29 | 안정화, 최신값 신선도 (**11/29 기능 동결**) |
| 4 | 11/30~12/6 | 서버 안정화 (12/8 최종 발표) |

## 확정 필요 항목

- Pi 5대의 센서ID ↔ BLE 이름/MAC ↔ 설치 장소 매핑 (현재 `app/seed.py`는 임의 매핑) — #1
- 센서가 없는 장소의 도착 판정 방식 (BLE / GPS / 수동) — #2
- 측정 시각 기준 (Unix 초 가정), 수업 서버 업로드 양식과의 호환
