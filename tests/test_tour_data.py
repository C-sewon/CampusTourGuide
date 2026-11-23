"""실제 투어 데이터(app/tour_data.py)가 서버에 들어갔을 때의 동작. (#22)"""
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import config, main, seed, tour_data
from app.database import Base, get_db


@pytest.fixture()
def tour_client(monkeypatch):
    """tests/test_api.py 의 client 와 같지만 샘플 대신 실제 투어 데이터를 넣는다."""
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(seed, "SessionLocal", Session)

    def override():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    main.app.dependency_overrides[get_db] = override
    seed.seed_if_empty(spots=tour_data.SPOTS, courses=tour_data.COURSES, card_spots=tour_data.CARD_SPOTS)
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def _spots_by_name(client):
    return {s["name"]: s for s in client.get("/api/spots").json()}


def _courses_by_name(client):
    return {c["name"]: c for c in client.get("/api/courses").json()}


def test_the_tour_has_12_spots_and_3_courses(tour_client):
    assert len(_spots_by_name(tour_client)) == 12
    assert list(_courses_by_name(tour_client)) == ["핵심 코스", "정문 코스", "학생 생활 코스"]


def test_no_sample_text_is_left(tour_client):
    for spot in tour_client.get("/api/spots").json():
        assert "[샘플]" not in spot["name"] and "[샘플]" not in (spot["description"] or "")
    for course in tour_client.get("/api/courses").json():
        assert "[샘플]" not in course["name"] and "[샘플]" not in (course["description"] or "")


def test_course_totals_match_the_numbers_from_the_issue(tour_client):
    totals = {name: c["total_minutes"] for name, c in _courses_by_name(tour_client).items()}
    assert totals == {"핵심 코스": 25, "정문 코스": 54, "학생 생활 코스": 49}


def test_per_stop_minutes_are_the_numbers_from_the_issue(tour_client):
    expected = {
        "핵심 코스": [("도서관 뒤편", 5), ("솔못", 13), ("사회과학대 입구", 7)],
        "정문 코스": [
            ("정문", 5), ("테니스장", 9), ("대학본부", 7), ("사회과학대 입구", 9),
            ("이마트24 건너편", 5), ("도서관 뒤편", 6), ("솔못", 13),
        ],
        "학생 생활 코스": [("개신문화관", 5), ("제1학생회관", 6), ("박물관", 19), ("야외공연장", 8), ("양성재", 11)],
    }
    for name, course in _courses_by_name(tour_client).items():
        stops = [(s["spot"]["name"], s["minutes"]) for s in sorted(course["spots"], key=lambda s: s["position"])]
        assert stops == expected[name], name


def test_the_four_sensor_spots_arrive_by_ble_and_the_rest_by_button(tour_client):
    spots = _spots_by_name(tour_client)
    auto = {name for name, s in spots.items() if s["arrival"]["method"] == "ble_auto"}
    assert auto == {"테니스장", "사회과학대 입구", "이마트24 건너편", "도서관 뒤편"}
    for name, s in spots.items():
        if name not in auto:
            assert s["arrival"] == {"method": "manual", "sensors": []}, name


def test_tennis_courts_use_both_sensors_in_priority_order(tour_client):
    sensors = _spots_by_name(tour_client)["테니스장"]["arrival"]["sensors"]
    assert [s["ble_name"] for s in sensors] == ["opensrc4", "opensrc5"]


def test_every_spot_has_coordinates(tour_client):
    for name, s in _spots_by_name(tour_client).items():
        assert s["latitude"] is not None and s["longitude"] is not None, name


def test_sensor_spots_use_the_sensor_coordinates_from_the_issue(tour_client):
    spots = _spots_by_name(tour_client)
    assert (spots["도서관 뒤편"]["latitude"], spots["도서관 뒤편"]["longitude"]) == (36.628895, 127.457839)
    # 사회과학대 입구는 센서 3, 이마트24 건너편은 센서 2의 좌표이다 (현장 확인, #43)
    assert (spots["사회과학대 입구"]["latitude"], spots["사회과학대 입구"]["longitude"]) == (36.629309, 127.45748)
    assert (spots["이마트24 건너편"]["latitude"], spots["이마트24 건너편"]["longitude"]) == (36.62974, 127.456406)
    # 테니스장은 센서 4, 5 좌표의 평균이고 이슈 표의 36.630600, 127.455014 와 같다
    assert spots["테니스장"]["latitude"] == pytest.approx(36.6306, abs=1e-5)
    assert spots["테니스장"]["longitude"] == pytest.approx(127.455014, abs=1e-5)


def test_spots_without_a_sensor_borrow_a_nearby_opensrc_sensor_when_one_is_close(tour_client):
    spots = _spots_by_name(tour_client)
    # 200m 안에 우리 센서가 있는 곳은 그 센서 값을 빌린다. (연구실 센서는 연구실 동기화 후에 등록됨)
    gate = spots["정문"]["value_source"]
    assert gate["is_on_site"] is False and gate["ble_name"] == "opensrc5"
    assert 0 < gate["distance_m"] <= config.MAX_BORROW_METERS
    pond = spots["솔못"]["value_source"]
    assert pond["is_on_site"] is False and pond["ble_name"] == "opensrc2"
    assert 0 < pond["distance_m"] <= config.MAX_BORROW_METERS


def test_a_comfortable_day_recommends_the_gate_course(tour_client):
    body = tour_client.get("/api/courses/recommend").json()
    assert body["condition"] == "comfortable"
    assert body["recommended_course_name"] == "정문 코스"


@pytest.mark.parametrize("setting,value,condition", [("COLD_MAX_CELSIUS", 40, "cold"), ("HOT_MIN_CELSIUS", 10, "hot")])
def test_a_cold_or_hot_day_recommends_the_short_course(tour_client, monkeypatch, setting, value, condition):
    monkeypatch.setattr(config, setting, value)
    body = tour_client.get("/api/courses/recommend").json()
    assert body["condition"] == condition
    assert body["recommended_course_name"] == "핵심 코스"


def test_the_short_course_is_the_shortest_and_the_gate_course_the_longest(tour_client):
    totals = {name: c["total_minutes"] for name, c in _courses_by_name(tour_client).items()}
    assert min(totals, key=totals.get) == "핵심 코스"
    assert max(totals, key=totals.get) == "정문 코스"


def test_the_sample_data_is_still_the_default_for_tests(tour_client):
    # seed_if_empty() 를 인자 없이 부르면 개발용 샘플이 들어간다 (다른 테스트가 샘플을 기대한다)
    assert seed.SPOTS is not tour_data.SPOTS
    assert any("[샘플]" in s[2] for s in seed.SPOTS)


def test_the_sensor_at_each_named_place_matches_the_field_check(tour_client):
    """현장 확인(#43, 11/23): 사회과학대 입구에서 센서 3이, 이마트24 건너편에서 센서 2가 잡힌다."""
    spots = _spots_by_name(tour_client)
    assert [s["ble_name"] for s in spots["사회과학대 입구"]["arrival"]["sensors"]] == ["opensrc3"]
    assert [s["ble_name"] for s in spots["이마트24 건너편"]["arrival"]["sensors"]] == ["opensrc2"]


# 장소 번호(id)는 이 순서대로 매겨진다. 앱이 방문 기록을 장소 번호로 폰에 저장하고, 앱의 기본 코스 목록도 같은 번호를 쓴다.
SPOT_ORDER = [
    "정문", "테니스장", "대학본부", "사회과학대 입구", "이마트24 건너편", "도서관 뒤편",
    "솔못", "개신문화관", "제1학생회관", "박물관", "야외공연장", "양성재",
]


def test_spot_order_is_stable():
    """장소 순서를 바꾸면 장소 번호가 밀려서 폰에 저장된 방문 기록과 어긋난다. 새 장소는 맨 뒤에 추가한다."""
    names = [spot[0] for spot in tour_data.SPOTS]
    assert names[: len(SPOT_ORDER)] == SPOT_ORDER, "장소 순서가 바뀌었습니다. 새 장소는 SPOTS 맨 뒤에 추가하세요."


def test_spot_ids_follow_the_order_in_the_server_response(tour_client):
    ids = {s["name"]: s["id"] for s in tour_client.get("/api/spots").json()}
    assert [ids[name] for name in SPOT_ORDER] == list(range(1, len(SPOT_ORDER) + 1))
