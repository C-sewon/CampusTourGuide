"""시연 전 서버 점검(app/check_server.py)."""
import copy

from app import check_server, config, lab_sync
from app.check_server import FAIL, OK, WARN, format_report, run_checks
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_tour_data import tour_client  # noqa: F401  (tour_client 는 pytest 픽스처)

NOW = 1_800_000_000


class FakeResponse:
    def __init__(self, body, content_type="application/json; charset=utf-8"):
        self._body = body
        self.headers = {"content-type": content_type}

    def json(self):
        return self._body


def reading(temp=20.0, stale=False):
    return {"temperature": temp, "is_stale": stale}


def spot(name, temp=20.0, stale=False, on_site=True, lat=36.62, card=False):
    return {
        "name": name,
        "latitude": lat,
        "has_card": card,
        "latest_reading": None if temp is None else reading(temp, stale),
        "value_source": None if temp is None else {"is_on_site": on_site},
    }


def good_world():
    spots = [spot("도서관 뒤편", card=True), spot("솔못", on_site=False, card=True), spot("정문", on_site=False)]
    courses = [
        {"name": "핵심 코스", "total_minutes": 25, "is_core": True,
         "spots": [{"spot": spots[0]}, {"spot": spots[1]}]},
        {"name": "정문 코스", "total_minutes": 54, "is_core": False,
         "spots": [{"spot": spots[2]}, {"spot": spots[0]}]},
    ]
    return {
        "/health": {"status": "ok"},
        "/api/lab-sync/status": {"enabled": True, "interval_seconds": 60, "last_error": None,
                                 "last_success": NOW - 30, "last_result": {"fetched": 36, "stored": 10}},
        "/api/sensors/status": [{"sensor_id": 1, "ble_name": "opensrc1", "status": "ok"},
                                {"sensor_id": 3, "ble_name": "opensrc3", "status": "invalid"}],
        "/api/spots": spots,
        "/api/courses": courses,
        "/api/courses/recommend": {"condition": "comfortable", "temperature": 20.0,
                                   "recommended_course_id": 2, "recommended_course_name": "정문 코스"},
    }


def make_get(world, content_type="application/json; charset=utf-8"):
    def get(path):
        return FakeResponse(copy.deepcopy(world[path]), content_type)

    return get


def levels(results, title):
    return [r.level for r in results if r.title == title]


def test_a_healthy_server_has_no_failures():
    results = run_checks(make_get(good_world()), now=NOW)
    assert not [r for r in results if r.level == FAIL], format_report(results)


def test_a_sensor_sending_only_error_values_is_a_warning_not_a_failure():
    results = run_checks(make_get(good_world()), now=NOW)
    assert levels(results, "오류값만 보내는 센서") == [WARN]


def test_unreachable_endpoint_is_reported_without_stopping_the_other_checks():
    get = make_get(good_world())

    def broken(path):
        if path == "/api/courses":
            raise ConnectionError("끊김")
        return get(path)

    results = run_checks(broken, now=NOW)
    assert any(r.level == FAIL and "점검 중 오류" in r.detail for r in results)
    assert levels(results, "서버 응답") == [OK]  # 다른 점검은 계속 된다


def test_lab_sync_that_never_succeeded_fails():
    world = good_world()
    world["/api/lab-sync/status"]["last_success"] = None
    assert levels(run_checks(make_get(world), now=NOW), "연구실 센서 가져오기") == [FAIL]


def test_lab_sync_with_an_error_fails():
    world = good_world()
    world["/api/lab-sync/status"]["last_error"] = "ConnectTimeout"
    assert levels(run_checks(make_get(world), now=NOW), "연구실 센서 가져오기") == [FAIL]


def test_old_lab_sync_success_fails():
    world = good_world()
    world["/api/lab-sync/status"]["last_success"] = NOW - 3600
    assert levels(run_checks(make_get(world), now=NOW), "연구실 센서 가져오기") == [FAIL]


def test_disabled_lab_sync_is_a_warning():
    world = good_world()
    world["/api/lab-sync/status"]["enabled"] = False
    assert levels(run_checks(make_get(world), now=NOW), "연구실 센서 가져오기") == [WARN]


def test_no_ok_sensor_fails():
    world = good_world()
    world["/api/sensors/status"] = [{"sensor_id": 1, "ble_name": "opensrc1", "status": "stale"}]
    assert levels(run_checks(make_get(world), now=NOW), "센서 상태") == [FAIL]


def test_spot_without_a_temperature_fails_and_is_named():
    world = good_world()
    world["/api/spots"][2] = spot("박물관", temp=None)
    results = run_checks(make_get(world), now=NOW)
    failed = [r for r in results if r.title == "장소 온도"][0]
    assert failed.level == FAIL and "박물관" in failed.detail


def test_implausible_temperature_fails():
    world = good_world()
    world["/api/spots"][0]["latest_reading"] = reading(73.0)
    results = run_checks(make_get(world), now=NOW)
    odd = [r for r in results if r.title == "이상한 온도"][0]
    assert odd.level == FAIL and "도서관 뒤편" in odd.detail


def test_stale_temperature_is_a_warning():
    world = good_world()
    world["/api/spots"][0]["latest_reading"] = reading(20.0, stale=True)
    assert levels(run_checks(make_get(world), now=NOW), "오래된 온도") == [WARN]


def test_missing_charset_header_fails():
    results = run_checks(make_get(good_world(), content_type="application/json"), now=NOW)
    assert levels(results, "한글 표시") == [FAIL]


def test_garbled_spot_name_fails():
    world = good_world()
    world["/api/spots"][0]["name"] = "�꿀꾼"
    assert levels(run_checks(make_get(world), now=NOW), "한글 표시") == [FAIL]


def test_one_course_only_fails():
    world = good_world()
    world["/api/courses"] = world["/api/courses"][:1]
    assert levels(run_checks(make_get(world), now=NOW), "코스") == [FAIL]


def test_two_core_courses_fail():
    world = good_world()
    for c in world["/api/courses"]:
        c["is_core"] = True
    assert levels(run_checks(make_get(world), now=NOW), "핵심 코스") == [FAIL]


def test_spot_without_coordinates_fails():
    world = good_world()
    world["/api/courses"][1]["spots"][0]["spot"]["latitude"] = None
    assert levels(run_checks(make_get(world), now=NOW), "장소 좌표") == [FAIL]


def test_old_server_without_is_core_and_has_card_only_warns():
    world = good_world()
    for c in world["/api/courses"]:
        del c["is_core"]
    for s in world["/api/spots"]:
        del s["has_card"]
    results = run_checks(make_get(world), now=NOW)
    assert levels(results, "핵심 코스") == [WARN]
    assert levels(results, "카드 장소") == [WARN]


def test_core_course_spot_without_a_card_fails():
    world = good_world()
    world["/api/courses"][0]["spots"][1]["spot"]["has_card"] = False
    assert levels(run_checks(make_get(world), now=NOW), "핵심 코스의 카드") == [FAIL]


def test_unknown_recommendation_is_a_warning():
    world = good_world()
    world["/api/courses/recommend"]["condition"] = "unknown"
    assert levels(run_checks(make_get(world), now=NOW), "코스 추천") == [WARN]


def test_no_recommended_course_fails():
    world = good_world()
    world["/api/courses/recommend"]["recommended_course_id"] = None
    assert levels(run_checks(make_get(world), now=NOW), "코스 추천") == [FAIL]


def test_report_names_the_failure_and_counts():
    world = good_world()
    world["/api/spots"][2] = spot("박물관", temp=None)
    text = format_report(run_checks(make_get(world), now=NOW))
    assert "[실패] 장소 온도" in text and "결과: 실패" in text


def test_the_real_tour_data_is_checked_end_to_end(tour_client, monkeypatch):
    """실제 투어 데이터를 켠 서버(테스트 DB)를 점검한다. 연구실 센서가 없으면 박물관, 야외공연장, 양성재만 온도가 없다."""
    monkeypatch.setattr(config, "LAB_SYNC_ENABLED", False)
    monkeypatch.setitem(lab_sync.state, "last_success", None)
    results = run_checks(tour_client.get)
    spots_result = [r for r in results if r.title == "장소 온도"][0]
    assert spots_result.level == FAIL
    for name in ("박물관", "야외공연장", "양성재"):
        assert name in spots_result.detail
    assert levels(results, "한글 표시") == [OK]
    # 핵심 코스(is_core)와 카드 장소(has_card)는 서버가 그 필드를 주면 정상이고, 예전 서버면 주의이다. 실패는 아니다
    assert FAIL not in levels(results, "핵심 코스")
    assert FAIL not in levels(results, "카드 장소")
    assert levels(results, "코스 추천") == [OK]


def test_main_reports_an_unreachable_server(capsys):
    code = check_server.main(["http://127.0.0.1:9"])  # 아무도 듣지 않는 포트
    out = capsys.readouterr().out
    assert code == 1 and "서버에 접속하지 못함" in out
