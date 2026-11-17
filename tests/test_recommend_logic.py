"""환경 맞춤 코스 추천의 판단 규칙 (DB 없이)."""
from app import recommend
from app.recommend import CourseInfo, build, choose, classify

SHORT = CourseInfo(id=1, name="짧은 코스", total_minutes=25, spot_ids=[1, 2, 4])
LONG = CourseInfo(id=2, name="기본 코스", total_minutes=45, spot_ids=[1, 2, 3, 4, 5])
COURSES = [SHORT, LONG]


def test_classify_uses_inclusive_boundaries():
    assert classify(5, cold_max=5, hot_min=27) == "cold"  # 5도 이하는 춥다
    assert classify(5.1, 5, 27) == "comfortable"
    assert classify(26.9, 5, 27) == "comfortable"
    assert classify(27, 5, 27) == "hot"  # 27도 이상은 덥다
    assert classify(-12, 5, 27) == "cold"
    assert classify(None, 5, 27) == "unknown"


def test_choose_shortest_when_cold_or_hot_and_longest_otherwise():
    assert choose(COURSES, "cold").id == 1
    assert choose(COURSES, "hot").id == 1
    assert choose(COURSES, "comfortable").id == 2
    assert choose(COURSES, "unknown").id == 2  # 기온을 모르면 기본 코스
    assert choose([], "cold") is None


def test_choose_breaks_ties_by_the_lower_course_id():
    a = CourseInfo(id=3, name="가", total_minutes=30, spot_ids=[])
    b = CourseInfo(id=4, name="나", total_minutes=30, spot_ids=[])
    assert choose([b, a], "cold").id == 3
    assert choose([b, a], "comfortable").id == 3


def test_cold_day_recommends_the_short_course_with_a_readable_reason():
    temps = {1: 3.0, 2: 4.0, 3: 2.0, 4: 3.0, 5: 3.0}
    result = build(COURSES, temps, cold_max=5, hot_min=27)
    assert result.condition == "cold"
    assert result.temperature == 3.0
    assert result.sample_count == 5
    assert result.recommended_course_id == 1
    assert result.reason == "기온 3.0도로 추워서 가장 짧은 코스(짧은 코스)를 추천합니다."
    assert [c.recommended for c in result.courses] == [True, False]


def test_hot_day_recommends_the_short_course():
    temps = {sid: 30.0 for sid in range(1, 6)}
    result = build(COURSES, temps, 5, 27)
    assert (result.condition, result.recommended_course_id) == ("hot", 1)
    assert "더워서" in result.reason


def test_comfortable_day_recommends_the_longest_course():
    temps = {sid: 20.0 for sid in range(1, 6)}
    result = build(COURSES, temps, 5, 27)
    assert (result.condition, result.recommended_course_id) == ("comfortable", 2)
    assert "활동하기 좋아" in result.reason


def test_temperature_is_the_average_of_distinct_spots_across_all_courses():
    # 장소 1, 2, 4 는 두 코스에 모두 있지만 한 번씩만 센다
    temps = {1: 10.0, 2: 10.0, 3: 20.0, 4: 10.0, 5: 20.0}
    result = build(COURSES, temps, 5, 27)
    assert result.sample_count == 5
    assert result.temperature == 14.0  # (10 + 10 + 20 + 10 + 20) / 5


def test_spots_without_a_value_are_left_out_of_the_average():
    temps = {1: 10.0, 2: None, 3: None, 4: 20.0, 5: None}
    result = build(COURSES, temps, 5, 27)
    assert result.sample_count == 2
    assert result.temperature == 15.0
    assert [c.average_temperature for c in result.courses] == [15.0, 15.0]


def test_unknown_when_no_spot_has_a_temperature():
    result = build(COURSES, {1: None}, 5, 27)
    assert result.condition == "unknown"
    assert result.temperature is None and result.sample_count == 0
    assert result.recommended_course_id == 2  # 기본 코스
    assert result.reason == "기온을 알 수 없어 기본 코스(가장 긴 코스, 기본 코스)를 추천합니다."
    assert all(c.average_temperature is None for c in result.courses)


def test_no_courses_means_no_recommendation():
    result = build([], {}, 5, 27)
    assert result.recommended_course_id is None
    assert result.recommended_course_name is None
    assert result.courses == []
    assert result.reason == "추천할 코스가 없습니다."


def test_thresholds_are_echoed_back():
    result = build(COURSES, {}, cold_max=7, hot_min=30)
    assert (result.thresholds.cold_max, result.thresholds.hot_min) == (7, 30)


def test_exactly_one_course_is_marked_recommended():
    result = build(COURSES, {sid: 20.0 for sid in range(1, 6)}, 5, 27)
    assert sum(c.recommended for c in result.courses) == 1
    assert recommend.COMFORTABLE == "comfortable"


def test_same_sensor_is_counted_once_across_spots():
    # 장소 3, 5 가 같은 센서 9 의 값을 빌려 써서 20.0 이 두 번 들어온 상황
    temps = {1: 10.0, 2: 10.0, 3: 20.0, 4: 10.0, 5: 20.0}
    sensors = {1: 1, 2: 2, 3: 9, 4: 4, 5: 9}
    result = build(COURSES, temps, 5, 27, spot_sensors=sensors)
    assert result.sample_count == 4
    assert result.temperature == 12.5  # (10 + 10 + 20 + 10) / 4, 센서 9 는 한 번만


def test_duplicate_sensors_are_removed_in_each_courses_average_too():
    temps = {1: 10.0, 2: 10.0, 3: 20.0, 4: 10.0, 5: 20.0}
    sensors = {1: 1, 2: 2, 3: 9, 4: 4, 5: 9}
    result = build(COURSES, temps, 5, 27, spot_sensors=sensors)
    by_id = {c.id: c.average_temperature for c in result.courses}
    assert by_id[1] == 10.0  # 짧은 코스: 장소 1, 2, 4
    assert by_id[2] == 12.5  # 기본 코스: 장소 1~5 중 센서 9 는 한 번


def test_spots_without_a_known_sensor_are_counted_individually():
    temps = {1: 10.0, 2: 20.0}
    result = build([CourseInfo(id=1, name="가", total_minutes=10, spot_ids=[1, 2])], temps, 5, 27)
    assert result.sample_count == 2 and result.temperature == 15.0


def test_dedup_does_not_depend_on_the_order_of_spot_ids():
    # 같은 센서를 쓰는 두 장소의 값이 다르면 번호가 작은 장소 값을 쓴다 (항상 같은 결과)
    temps = {1: 12.0, 2: 14.0}
    sensors = {1: 7, 2: 7}
    a = build([CourseInfo(id=1, name="가", total_minutes=10, spot_ids=[1, 2])], temps, 5, 27, sensors)
    b = build([CourseInfo(id=1, name="가", total_minutes=10, spot_ids=[2, 1])], temps, 5, 27, sensors)
    assert a.temperature == b.temperature == 12.0
