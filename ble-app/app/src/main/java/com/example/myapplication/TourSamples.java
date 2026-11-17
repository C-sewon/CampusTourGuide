package com.example.myapplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * [샘플] 서버 주소를 넣지 않았을 때 화면에 보여줄 데이터.
 * 장소, 코스, 센서 좌표는 서버 시드(app/seed.py)와 같고, 기온·습도·측정 시각은 샘플이다.
 * 서버 응답과 같은 모양(HomeApi)으로 만들어 화면 코드가 서버/샘플을 구분하지 않게 한다.
 */
public final class TourSamples {

    private TourSamples() {}

    public static List<HomeApi.Course> courses() {
        HomeApi.Spot library = spot(1, "도서관 뒤편", "[샘플] 진로창업본부 입구가 있는 곳.",
                36.628895, 127.457839, "ble_auto", new int[]{1},
                reading(22.0, 45.0, 3), source("도서관 뒤편", true, 0));
        HomeApi.Spot social = spot(2, "사회과학대 입구", "[샘플] 사회과학대학 입구.",
                36.62974, 127.456406, "ble_auto", new int[]{2},
                reading(28.0, 50.0, 2), source("사회과학대 입구", true, 0));
        HomeApi.Spot emart = spot(3, "이마트24 건너편", "[샘플] 사회과학대 옆 길 건너편.",
                36.629309, 127.45748, "ble_auto", new int[]{3}, null, null);
        HomeApi.Spot tennis = spot(4, "테니스장", "[샘플] 테니스장 앞과 건너편 길.",
                36.6306005, 127.455014, "ble_auto", new int[]{4, 5},
                reading(31.0, 40.0, 5), source("테니스장 건너편", true, 0));
        HomeApi.Spot noSensor = spot(5, "[샘플] 센서 없는 장소", "[샘플] 센서가 없어 가까운 센서 값을 빌려 쓰는 장소.",
                null, null, "manual", new int[]{},
                reading(28.0, 50.0, 2), source("사회과학대 입구", false, null));

        return Arrays.asList(
                course(1, "[샘플] 짧은 코스", "더위/추위에 추천하는 짧은 코스",
                        stop(1, 5, library), stop(2, 10, social), stop(3, 10, tennis)),
                course(2, "[샘플] 기본 코스", "장소를 모두 도는 기본 코스",
                        stop(1, 5, library), stop(2, 10, social), stop(3, 10, emart),
                        stop(4, 10, tennis), stop(5, 10, noSensor)));
    }

    /** 샘플 기온(평균 27℃)은 덥다 → 짧은 코스 추천 */
    public static HomeApi.Recommendation recommendation() {
        HomeApi.Recommendation rec = new HomeApi.Recommendation();
        rec.condition = "hot";
        rec.temperature = 27.0;
        rec.recommendedCourseId = 1;
        rec.recommendedCourseName = "[샘플] 짧은 코스";
        rec.courses = new ArrayList<>();
        for (HomeApi.Course c : courses()) {
            HomeApi.CourseChoice choice = new HomeApi.CourseChoice();
            choice.id = c.id;
            choice.totalMinutes = c.totalMinutes;
            choice.averageTemperature = 27.0;
            choice.recommended = c.id == 1;
            rec.courses.add(choice);
        }
        return rec;
    }

    public static HomeApi.Course course(int id) {
        for (HomeApi.Course c : courses()) if (c.id == id) return c;
        return null;
    }

    private static HomeApi.Course course(int id, String name, String description, HomeApi.CourseSpot... stops) {
        HomeApi.Course c = new HomeApi.Course();
        c.id = id;
        c.name = name;
        c.description = description;
        c.spots = Arrays.asList(stops);
        for (HomeApi.CourseSpot s : stops) c.totalMinutes += s.minutes;
        return c;
    }

    private static HomeApi.CourseSpot stop(int position, int minutes, HomeApi.Spot spot) {
        HomeApi.CourseSpot cs = new HomeApi.CourseSpot();
        cs.position = position;
        cs.minutes = minutes;
        cs.spot = spot;
        return cs;
    }

    private static HomeApi.Spot spot(int id, String name, String description, Double lat, Double lon,
                                     String method, int[] sensorIds,
                                     HomeApi.Reading reading, HomeApi.ValueSource source) {
        HomeApi.Spot s = new HomeApi.Spot();
        s.id = id;
        s.name = name;
        s.description = description;
        s.latitude = lat;
        s.longitude = lon;
        s.arrival = new HomeApi.Arrival();
        s.arrival.method = method;
        s.arrival.sensors = new ArrayList<>();
        for (int sensorId : sensorIds) {
            HomeApi.SpotSensor ss = new HomeApi.SpotSensor();
            ss.sensorId = sensorId;
            s.arrival.sensors.add(ss);
        }
        s.latestReading = reading;
        s.valueSource = source;
        return s;
    }

    private static HomeApi.Reading reading(double celsius, Double humidity, int ageMinutes) {
        HomeApi.Reading r = new HomeApi.Reading();
        r.temperature = celsius;
        r.humidity = humidity;
        r.ageMinutes = ageMinutes;
        return r;
    }

    private static HomeApi.ValueSource source(String location, boolean onSite, Integer distanceM) {
        HomeApi.ValueSource v = new HomeApi.ValueSource();
        v.location = location;
        v.isOnSite = onSite;
        v.distanceM = distanceM;
        return v;
    }
}
