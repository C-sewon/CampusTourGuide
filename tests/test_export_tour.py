"""앱에 넣어 둘 기본 코스 목록(app/export_tour.py). (#37)"""
import json

from app import export_tour, tour_data
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_tour_data import tour_client  # noqa: F401  (tour_client 는 pytest 픽스처)

READING_FIELDS = ("latest_reading", "recent_average", "value_source")


def without_readings(courses):
    """서버 응답에서 온도 칸만 비운 복사본. 뽑은 목록과 구조를 비교하려고 쓴다."""
    courses = json.loads(json.dumps(courses))
    for course in courses:
        for stop in course["spots"]:
            for field in READING_FIELDS:
                stop["spot"][field] = None
    return courses


def test_snapshot_has_the_real_tour_courses_and_spots():
    snapshot = export_tour.build_snapshot()
    assert [c["name"] for c in snapshot] == ["핵심 코스", "정문 코스", "학생 생활 코스"]
    spots = {stop["spot"]["name"] for c in snapshot for stop in c["spots"]}
    assert len(spots) == 12


def test_snapshot_has_no_temperatures():
    # 예전 온도가 기본 목록에 들어 있으면 서버가 안 될 때 현재 온도로 잘못 보인다
    for course in export_tour.build_snapshot():
        for stop in course["spots"]:
            for field in READING_FIELDS:
                assert stop["spot"][field] is None, (stop["spot"]["name"], field)


def test_snapshot_keeps_what_the_app_needs_offline():
    snapshot = export_tour.build_snapshot()
    core = [c for c in snapshot if c["is_core"]]
    assert [c["name"] for c in core] == ["핵심 코스"]
    spots = {stop["spot"]["name"]: stop["spot"] for c in snapshot for stop in c["spots"]}
    # 센서를 감지했을 때 어느 장소인지 알 수 있다
    assert [s["ble_name"] for s in spots["테니스장"]["arrival"]["sensors"]] == ["opensrc4", "opensrc5"]
    assert [s["ble_name"] for s in spots["도서관 뒤편"]["arrival"]["sensors"]] == ["opensrc1"]
    assert spots["박물관"]["arrival"] == {"method": "manual", "sensors": []}
    # 카드 장소와 좌표, 소개
    assert {n for n, s in spots.items() if s["has_card"]} == tour_data.CARD_SPOTS
    assert all(s["latitude"] is not None and s["longitude"] is not None for s in spots.values())
    assert all(s["description"] for s in spots.values())


def test_snapshot_has_the_same_structure_as_the_server_response(tour_client):
    """서버의 /api/courses 응답에서 온도 칸만 비우면 뽑은 목록과 똑같다. (서버 응답 모양이 바뀌면 여기서 알 수 있다)"""
    live = tour_client.get("/api/courses").json()
    assert export_tour.build_snapshot() == without_readings(live)


def test_the_committed_file_matches_the_current_tour_data():
    """장소나 코스를 고치고 docs/tour_default.json 을 다시 뽑지 않으면 실패한다.

    다시 뽑는 법: python -m app.export_tour (그리고 앱 담당에게 새 파일을 알린다)
    """
    committed = export_tour.DEFAULT_PATH.read_text(encoding="utf-8")
    assert committed == export_tour.to_json(export_tour.build_snapshot()), (
        "docs/tour_default.json 이 현재 장소와 코스와 다릅니다. python -m app.export_tour 로 다시 뽑아 주세요."
    )


def test_json_keeps_korean_text_as_it_is_and_ends_with_a_newline():
    text = export_tour.to_json(export_tour.build_snapshot())
    assert "핵심 코스" in text and "\\u" not in text
    assert text.endswith("\n")
    assert json.loads(text)[0]["name"] == "핵심 코스"


def test_cli_writes_the_file_in_utf8(tmp_path, capsys):
    target = tmp_path / "아래" / "tour.json"
    assert export_tour.main_cli([str(target)]) == 0
    assert json.loads(target.read_text(encoding="utf-8"))[0]["name"] == "핵심 코스"
    assert "3개" in capsys.readouterr().out


def test_cli_prints_to_the_screen_with_a_dash(capsys):
    assert export_tour.main_cli(["-"]) == 0
    assert json.loads(capsys.readouterr().out)[1]["name"] == "정문 코스"


def test_exporting_does_not_touch_the_servers_database(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)  # 서버 DB 기본 경로(./campus_tour.db)가 여기라면 만들어졌을 것이다
    export_tour.main_cli([str(tmp_path / "out.json")])
    assert not (tmp_path / "campus_tour.db").exists()


def test_exporting_twice_gives_the_same_file():
    assert export_tour.to_json(export_tour.build_snapshot()) == export_tour.to_json(export_tour.build_snapshot())
