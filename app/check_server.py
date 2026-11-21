"""서버 점검: 시연 전에 서버가 정상인지 한 번에 확인한다.

사용 (서버를 켜 둔 상태에서):
    python -m app.check_server http://192.168.0.104:8000
주소를 생략하면 http://localhost:8000 을 점검한다. 실패가 하나라도 있으면 종료 코드 1로 끝난다.

README "서버 열기"의 시연 전 점검 표를 자동으로 확인한다. 서버에 값을 쓰지 않고 읽기만 한다.
"""
import sys
import time
from dataclasses import dataclass

from . import processing

OK, WARN, FAIL = "ok", "warn", "fail"
LABELS = {OK: "[정상]", WARN: "[주의]", FAIL: "[실패]"}

# 연구실 값을 마지막으로 가져온 지 이 시간(초)이 넘으면 실패로 본다. (가져오는 간격의 3배와 3분 중 큰 값)
MIN_SYNC_AGE_LIMIT = 180


@dataclass
class Result:
    level: str
    title: str
    detail: str = ""


def _names(items, limit=6):
    items = list(items)
    text = ", ".join(items[:limit])
    return text + (f" 외 {len(items) - limit}곳" if len(items) > limit else "")


def check_health(get):
    body = get("/health").json()
    if body.get("status") == "ok":
        return [Result(OK, "서버 응답", '/health 가 {"status":"ok"}')]
    return [Result(FAIL, "서버 응답", f"/health 응답이 이상함: {body}")]


def check_lab_sync(get, now):
    status = get("/api/lab-sync/status").json()
    if not status.get("enabled"):
        return [Result(WARN, "연구실 센서 가져오기", "꺼져 있음(LAB_SYNC_ENABLED=0). 센서 값은 샘플이거나 비어 있음")]
    if status.get("last_error"):
        return [Result(FAIL, "연구실 센서 가져오기", f"마지막 시도가 실패함: {status['last_error']}")]
    last_success = status.get("last_success")
    if last_success is None:
        return [Result(FAIL, "연구실 센서 가져오기", "아직 한 번도 성공하지 못함. 서버를 켠 지 1분이 안 됐거나 연구실 서버에 접속이 안 됨(학교 네트워크인지 확인)")]
    limit = max(MIN_SYNC_AGE_LIMIT, 3 * status.get("interval_seconds", 60))
    age = now - last_success
    if age > limit:
        return [Result(FAIL, "연구실 센서 가져오기", f"마지막 성공이 {int(age // 60)}분 전임")]
    result = status.get("last_result") or {}
    detail = f"마지막 성공 {int(age)}초 전, 센서 {result.get('fetched', '?')}개 중 {result.get('stored', '?')}개 저장"
    return [Result(OK, "연구실 센서 가져오기", detail)]


def check_sensors(get):
    sensors = get("/api/sensors/status").json()
    by_status = {}
    for s in sensors:
        by_status.setdefault(s["status"], []).append(s)
    if not by_status.get("ok"):
        return [Result(FAIL, "센서 상태", "정상(ok) 센서가 하나도 없음")]
    counts = ", ".join(f"{k} {len(v)}" for k, v in sorted(by_status.items()))
    results = [Result(OK, "센서 상태", f"정상 {len(by_status['ok'])}대 ({counts})")]
    bad = [s for s in sensors if s["status"] == "invalid"]
    if bad:
        names = [f"{s.get('ble_name') or s['sensor_id']}" for s in bad]
        results.append(Result(WARN, "오류값만 보내는 센서", f"{_names(names)} (값은 계산에서 제외됨. 고장 센서면 정상)"))
    return results


def check_spots(get):
    response = get("/api/spots")
    spots = response.json()
    results = []

    charset_ok = response.headers.get("content-type", "") == "application/json; charset=utf-8"
    garbled = [s["name"] for s in spots if "�" in s["name"] or not s["name"].strip()]
    if not charset_ok or garbled:
        detail = "응답 헤더에 charset=utf-8 이 없음" if not charset_ok else f"이름이 깨진 장소: {_names(garbled)}"
        results.append(Result(FAIL, "한글 표시", detail))
    else:
        results.append(Result(OK, "한글 표시", "응답 헤더에 charset=utf-8 이 있고 장소 이름이 정상"))

    if not spots:
        return results + [Result(FAIL, "장소 목록", "장소가 하나도 없음")]

    missing = [s["name"] for s in spots if s["latest_reading"] is None]
    stale = [s["name"] for s in spots if s["latest_reading"] and s["latest_reading"]["is_stale"]]
    low, high = processing.TEMPERATURE_RANGE
    odd = [s["name"] for s in spots if s["latest_reading"] and not (low <= s["latest_reading"]["temperature"] <= high)]
    with_temp = len(spots) - len(missing)
    results.append(Result(FAIL if missing else OK, "장소 온도", f"{len(spots)}곳 중 {with_temp}곳에 온도가 있음" + (f". 온도 없음: {_names(missing)}" if missing else "")))
    if stale:
        results.append(Result(WARN, "오래된 온도", f"{_names(stale)} (최신 값이 아님으로 표시됨)"))
    if odd:
        results.append(Result(FAIL, "이상한 온도", f"{_names(odd)} ({low:.0f}~{high:.0f}℃ 밖. 고장 센서 값이 섞였는지 확인)"))

    borrowed = [s["name"] for s in spots if s["value_source"] and not s["value_source"]["is_on_site"]]
    if borrowed:
        results.append(Result(OK, "가까운 센서 값 빌림", f"{len(borrowed)}곳: {_names(borrowed)}"))
    return results


def check_courses(get):
    courses = get("/api/courses").json()
    if len(courses) < 2:
        return [Result(FAIL, "코스", f"코스가 {len(courses)}개뿐임. 추천은 길이가 다른 코스가 2개 이상 있어야 함")]
    totals = ", ".join(f"{c['name']} {c['total_minutes']}분" for c in courses)
    results = [Result(OK, "코스", f"{len(courses)}개: {totals}")]

    if any("is_core" not in c for c in courses):
        results.append(Result(WARN, "핵심 코스", "코스 응답에 is_core 가 없음(서버가 예전 버전)"))
    else:
        core = [c["name"] for c in courses if c["is_core"]]
        results.append(Result(OK if len(core) == 1 else FAIL, "핵심 코스", f"핵심 코스: {_names(core) or '없음'}" + ("" if len(core) == 1 else " (하나만 있어야 함)")))

    no_coordinates = sorted({stop["spot"]["name"] for c in courses for stop in c["spots"] if stop["spot"]["latitude"] is None})
    if no_coordinates:
        results.append(Result(FAIL, "장소 좌표", f"좌표가 없는 장소: {_names(no_coordinates)} (가까운 센서를 못 찾고 앱 지도에도 안 나옴)"))
    return results


def check_cards(get):
    spots = get("/api/spots").json()
    if not spots or "has_card" not in spots[0]:
        return [Result(WARN, "카드 장소", "장소 응답에 has_card 가 없음(서버가 예전 버전)")]
    card = [s["name"] for s in spots if s["has_card"]]
    results = [Result(OK if card else WARN, "카드 장소", f"{len(card)}곳: {_names(card)}" if card else "카드를 띄우는 장소가 없음")]
    courses = get("/api/courses").json()
    core = next((c for c in courses if c.get("is_core")), None)
    if core:
        without_card = [s["spot"]["name"] for s in core["spots"] if not s["spot"]["has_card"]]
        if without_card:
            results.append(Result(FAIL, "핵심 코스의 카드", f"카드가 없는 핵심 코스 장소: {_names(without_card)}"))
    return results


def check_recommend(get):
    body = get("/api/courses/recommend").json()
    if body["recommended_course_id"] is None:
        return [Result(FAIL, "코스 추천", "추천할 코스가 없음")]
    if body["condition"] == "unknown":
        return [Result(WARN, "코스 추천", f"기온을 알 수 없어 기본 코스를 추천함: {body['recommended_course_name']}")]
    return [Result(OK, "코스 추천", f"평균 {body['temperature']}℃ ({body['condition']}) → {body['recommended_course_name']}")]


def run_checks(get, now=None):
    """모든 점검을 실행해 결과 목록을 돌려준다. get 은 경로를 받아 응답을 돌려주는 함수이다."""
    now = time.time() if now is None else now
    checks = [
        ("서버 응답", lambda: check_health(get)),
        ("연구실 센서 가져오기", lambda: check_lab_sync(get, now)),
        ("센서 상태", lambda: check_sensors(get)),
        ("장소", lambda: check_spots(get)),
        ("코스", lambda: check_courses(get)),
        ("카드 장소", lambda: check_cards(get)),
        ("코스 추천", lambda: check_recommend(get)),
    ]
    results = []
    for title, check in checks:
        try:
            results.extend(check())
        except Exception as exc:  # 한 점검이 깨져도 나머지는 계속 한다
            results.append(Result(FAIL, title, f"점검 중 오류: {type(exc).__name__}: {exc}"))
    return results


def format_report(results):
    lines = [f"{LABELS[r.level]} {r.title}" + (f": {r.detail}" if r.detail else "") for r in results]
    fails = sum(r.level == FAIL for r in results)
    warns = sum(r.level == WARN for r in results)
    lines.append("")
    lines.append(f"결과: 실패 {fails}, 주의 {warns}, 정상 {len(results) - fails - warns}")
    return "\n".join(lines)


def main(argv=None):
    import httpx

    argv = sys.argv[1:] if argv is None else argv
    base = (argv[0] if argv else "http://localhost:8000").rstrip("/")
    if not base.startswith("http"):
        base = "http://" + base
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass
    print(f"서버 점검: {base}\n")
    with httpx.Client(base_url=base, timeout=10) as client:
        try:
            client.get("/health")  # 먼저 접속이 되는지 본다
        except httpx.HTTPError as exc:
            print(f"[실패] 서버에 접속하지 못함: {type(exc).__name__}: {exc}")
            print("서버가 켜져 있는지, 주소와 포트(8000)가 맞는지, 같은 Wi-Fi인지 확인하세요.")
            return 1
        results = run_checks(client.get)
    print(format_report(results))
    return 1 if any(r.level == FAIL for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
