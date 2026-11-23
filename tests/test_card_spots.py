"""카드가 뜨는 장소(has_card). 앱이 장소에 도착했을 때 카드를 띄울지 이 값으로 정한다. (#37)"""
import pytest
from sqlalchemy import create_engine, text

from app import tour_data
from app.database import ensure_current_schema
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_tour_data import tour_client  # noqa: F401  (tour_client 는 pytest 픽스처)

CARD = {"도서관 뒤편", "솔못", "사회과학대 입구", "개신문화관", "박물관", "야외공연장"}


def _by_name(client):
    return {s["name"]: s for s in client.get("/api/spots").json()}


def test_these_six_spots_show_a_card(tour_client):
    spots = _by_name(tour_client)
    assert {name for name, s in spots.items() if s["has_card"]} == CARD


def test_the_other_six_spots_do_not_show_a_card(tour_client):
    spots = _by_name(tour_client)
    others = {name for name, s in spots.items() if not s["has_card"]}
    assert others == {"정문", "테니스장", "대학본부", "이마트24 건너편", "제1학생회관", "양성재"}


def test_every_spot_of_the_core_course_shows_a_card(tour_client):
    core = next(c for c in tour_client.get("/api/courses").json() if c["is_core"])
    assert all(s["spot"]["has_card"] for s in core["spots"])


def test_the_student_life_course_has_three_card_spots(tour_client):
    course = next(c for c in tour_client.get("/api/courses").json() if c["name"] == "학생 생활 코스")
    card_spots = [s["spot"]["name"] for s in course["spots"] if s["spot"]["has_card"]]
    assert card_spots == ["개신문화관", "박물관", "야외공연장"]


def test_card_spots_in_the_courses_response_match_the_spots_response(tour_client):
    spots = _by_name(tour_client)
    for course in tour_client.get("/api/courses").json():
        for stop in course["spots"]:
            assert stop["spot"]["has_card"] == spots[stop["spot"]["name"]]["has_card"]


def test_every_card_spot_name_exists_in_the_tour_data():
    # 장소 이름을 잘못 적으면 카드가 조용히 안 뜬다
    names = {spot[0] for spot in tour_data.SPOTS}
    assert tour_data.CARD_SPOTS <= names


def test_sample_data_has_no_card_spots(client):
    assert not any(s["has_card"] for s in client.get("/api/spots").json())


def test_existing_fields_of_the_spots_response_are_unchanged(tour_client):
    spot = tour_client.get("/api/spots/1").json()
    assert {"id", "name", "name_en", "description", "latitude", "longitude", "arrival", "latest_reading",
            "value_source"} <= set(spot)


def test_old_spots_table_without_has_card_is_detected():
    old = create_engine("sqlite://")
    with old.begin() as conn:  # 카드 표시가 생기기 전의 spots 표 (센서 연결은 새 구조)
        conn.execute(text("CREATE TABLE spots (id INTEGER PRIMARY KEY, name TEXT, latitude FLOAT, longitude FLOAT)"))
    with pytest.raises(RuntimeError, match="spots"):
        ensure_current_schema(old)
