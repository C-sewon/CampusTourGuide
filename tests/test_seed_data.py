"""장소와 코스 데이터의 짜임새. 장소를 넣거나 고칠 때(#22) 실수를 잡아 준다.

개발용 샘플(app/seed.py)과 실제 투어 데이터(app/tour_data.py)를 같은 기준으로 확인한다.
데이터를 고친 뒤 이 테스트가 통과하는지 확인한다.
"""
import pytest

from app import seed, tour_data
from tests.test_api import client  # noqa: F401  (client 는 pytest 픽스처)

# (이름, 장소, 코스)
DATASETS = [
    pytest.param(seed.SPOTS, seed.COURSES, id="sample"),
    pytest.param(tour_data.SPOTS, tour_data.COURSES, id="tour"),
]


def _sensor_ids():
    return {row[0] for row in seed.SENSORS}


def _explicit_coordinates(spot):
    return spot[4] if len(spot) > 4 else None


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_spot_names_are_unique_and_not_empty(spots, courses):
    names = [s[0] for s in spots]
    assert all(n.strip() for n in names)
    assert len(names) == len(set(names))


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_every_spot_links_only_to_registered_sensors(spots, courses):
    for spot in spots:
        for sensor_id, _on_site in spot[3]:
            assert sensor_id in _sensor_ids(), f"{spot[0]}: 등록되지 않은 센서 {sensor_id}"


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_every_spot_has_a_way_to_show_a_temperature(spots, courses):
    # 센서 연결도 좌표도 없으면 그 장소에는 온도가 나올 수 없다. (좌표가 있으면 가까운 센서를 찾는다)
    for spot in spots:
        assert spot[3] or _explicit_coordinates(spot) is not None, f"{spot[0]}: 센서 연결도 좌표도 없음"


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_explicit_coordinates_look_like_the_campus(spots, courses):
    # 캠퍼스(청주) 근처인지만 본다. 위도와 경도를 바꿔 적는 실수를 잡는다
    for spot in spots:
        coords = _explicit_coordinates(spot)
        if coords is None:
            continue
        lat, lon = coords
        assert 36.60 < lat < 36.66, f"{spot[0]}: 위도 {lat} 가 캠퍼스 근처가 아님 (경도와 바꿔 적었는지 확인)"
        assert 127.43 < lon < 127.48, f"{spot[0]}: 경도 {lon} 가 캠퍼스 근처가 아님 (위도와 바꿔 적었는지 확인)"


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_courses_refer_to_existing_spots_with_positive_minutes(spots, courses):
    for name, _en, _desc, stops, *_ in courses:
        assert stops, f"{name}: 장소가 없음"
        for spot_number, minutes in stops:
            assert 1 <= spot_number <= len(spots), f"{name}: 없는 장소 번호 {spot_number}"
            assert minutes > 0, f"{name}: 소요시간이 0 이하"
        numbers = [n for n, _ in stops]
        assert len(numbers) == len(set(numbers)), f"{name}: 같은 장소가 두 번 들어 있음"


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_course_names_are_unique(spots, courses):
    names = [c[0] for c in courses]
    assert len(names) == len(set(names))


@pytest.mark.parametrize("spots,courses", DATASETS)
def test_there_are_a_short_and_a_long_course_for_the_recommendation(spots, courses):
    # 코스 추천은 가장 짧은 코스와 가장 긴 코스 중에서 고른다. 모두 같은 길이면 추천이 의미가 없다.
    totals = [sum(m for _, m in c[3]) for c in courses]
    assert len(totals) >= 2
    assert min(totals) < max(totals)


def test_a_typed_coordinate_wins_over_the_sensor_average():
    assert seed._spot_coordinates([(1, True)], (36.6, 127.45)) == (36.6, 127.45)


def test_without_a_typed_coordinate_the_installed_sensors_average_is_used():
    lat1, lon1 = seed.SENSORS[3][5], seed.SENSORS[3][6]
    lat2, lon2 = seed.SENSORS[4][5], seed.SENSORS[4][6]
    lat, lon = seed._spot_coordinates([(4, True), (5, True)])
    assert (lat, lon) == ((lat1 + lat2) / 2, (lon1 + lon2) / 2)


def test_a_spot_with_only_a_borrowed_sensor_has_no_coordinates_unless_typed():
    assert seed._spot_coordinates([(2, False)]) == (None, None)
    assert seed._spot_coordinates([(2, False)], (36.629, 127.456)) == (36.629, 127.456)


def test_a_real_spot_without_a_sensor_borrows_the_nearest_sensor_by_its_typed_coordinate(client):
    """센서가 없는 실제 장소(솔못 등)는 좌표만 적으면 가까운 센서 값을 빌린다. (#22 에서 장소를 넣는 방식)"""
    from sqlalchemy import select

    from app import models

    sensor = seed.SENSORS[1]  # 센서 2 (사회과학대 입구) 바로 옆 좌표로 장소를 만든다
    near = (sensor[5] + 0.0002, sensor[6])  # 위도 0.0002도는 약 22m
    spot = ("솔못", "Solmot Pond", "연못", [], near)

    # client 픽스처가 이미 시드를 넣었으므로, 장소 하나를 같은 방식으로 직접 추가해 확인한다
    with seed.SessionLocal() as db:
        lat, lon = seed._spot_coordinates(spot[3], spot[4])
        db.add(models.Spot(name=spot[0], name_en=spot[1], description=spot[2], latitude=lat, longitude=lon))
        db.commit()
        spot_id = db.scalar(select(models.Spot.id).where(models.Spot.name == "솔못"))

    body = client.get(f"/api/spots/{spot_id}").json()
    assert body["arrival"]["method"] == "manual"  # 설치된 센서가 없으니 도착 버튼
    assert body["value_source"]["is_on_site"] is False
    assert body["value_source"]["sensor_id"] == 2  # 가장 가까운 센서
    assert 0 < body["value_source"]["distance_m"] < 50
    assert body["latest_reading"] is not None
