"""핵심 코스 여부(is_core). 자유 탐색의 핵심 코스 카드(#37)가 이 값으로 핵심 코스의 장소를 찾는다."""
import pytest
from sqlalchemy import create_engine, text

from app import seed, tour_data
from app.database import ensure_current_schema
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)
from tests.test_tour_data import tour_client  # noqa: F401  (tour_client 는 pytest 픽스처)


def _core_flags(courses):
    return [bool(c[4]) if len(c) > 4 else False for c in courses]


def test_courses_response_has_is_core_for_every_course(client):
    courses = client.get("/api/courses").json()
    assert all(isinstance(c["is_core"], bool) for c in courses)


def test_sample_data_marks_the_short_course_as_core(client):
    by_name = {c["name"]: c for c in client.get("/api/courses").json()}
    assert by_name["짧은 코스"]["is_core"] is True
    assert by_name["기본 코스"]["is_core"] is False


def test_the_tour_has_exactly_one_core_course_and_it_is_the_core_course(tour_client):
    courses = tour_client.get("/api/courses").json()
    core = [c for c in courses if c["is_core"]]
    assert [c["name"] for c in core] == ["핵심 코스"]


def test_the_core_course_spots_are_the_three_spots_for_the_card(tour_client):
    core = next(c for c in tour_client.get("/api/courses").json() if c["is_core"])
    spots = [s["spot"]["name"] for s in sorted(core["spots"], key=lambda s: s["position"])]
    assert spots == ["도서관 뒤편", "솔못", "사회과학대 입구"]


def test_the_core_course_is_the_shortest_course_so_the_card_matches_the_recommendation(tour_client):
    courses = tour_client.get("/api/courses").json()
    shortest = min(courses, key=lambda c: c["total_minutes"])
    assert shortest["is_core"] is True


@pytest.mark.parametrize("courses,label", [(seed.COURSES, "sample"), (tour_data.COURSES, "tour")])
def test_at_most_one_core_course_in_the_data(courses, label):
    assert sum(_core_flags(courses)) <= 1, f"{label}: 핵심 코스는 하나만 둔다"


def test_the_tour_data_has_a_core_course():
    assert sum(_core_flags(tour_data.COURSES)) == 1


def test_existing_fields_of_the_courses_response_are_unchanged(tour_client):
    course = tour_client.get("/api/courses").json()[0]
    assert {"id", "name", "name_en", "description", "total_minutes", "spots"} <= set(course)
    assert {"position", "minutes", "spot"} <= set(course["spots"][0])


def test_old_courses_table_without_is_core_is_detected():
    old = create_engine("sqlite://")
    with old.begin() as conn:  # 핵심 코스 표시가 생기기 전의 courses 표
        conn.execute(text("CREATE TABLE courses (id INTEGER PRIMARY KEY, name TEXT, name_en TEXT, description TEXT)"))
    with pytest.raises(RuntimeError, match="courses"):
        ensure_current_schema(old)
