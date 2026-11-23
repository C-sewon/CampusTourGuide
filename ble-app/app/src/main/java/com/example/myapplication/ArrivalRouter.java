package com.example.myapplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "센서 N 도착"을 장소로 바꾼다.
 *
 * 서버 코스 데이터의 장소마다 arrival.sensors(이 장소에 설치된 센서)가 있다.
 * 센서가 감지되면 그 센서가 들어 있는 모든 장소를 도착으로 본다. 지금 안내 중인 장소인지와는 상관없다. (#37)
 * 예: 센서 4 또는 5 → 테니스장, 센서 1 → 도서관 뒤편 (어느 코스를 걷고 있든)
 *
 * 화면이 코스 목록을 받을 때마다 update()로 알려 준다. Android 기능을 쓰지 않아 PC에서 테스트할 수 있다.
 */
public final class ArrivalRouter {

    private static Map<Integer, List<Integer>> sensorToSpots = Collections.emptyMap();

    private ArrivalRouter() {}

    /** 코스 목록을 받으면 부른다 (서버 응답 또는 샘플). null이나 빈 목록이면 이전 값을 지우지 않는다 */
    public static synchronized void update(List<HomeApi.Course> courses) {
        Map<Integer, List<Integer>> built = build(courses);
        if (!built.isEmpty()) sensorToSpots = built;
    }

    /** 이 센서가 설치된 장소 id 목록. 모르는 센서면 빈 목록 */
    public static synchronized List<Integer> spotIdsForSensor(int sensorId) {
        List<Integer> ids = sensorToSpots.get(sensorId);
        return ids == null ? Collections.emptyList() : ids;
    }

    /** 코스 목록에서 센서 → 장소 id 목록을 만든다. 같은 장소가 여러 코스에 있어도 한 번만 넣는다 */
    static Map<Integer, List<Integer>> build(List<HomeApi.Course> courses) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        if (courses == null) return map;
        Map<Integer, HomeApi.Spot> spots = new LinkedHashMap<>();
        for (HomeApi.Course c : courses) {
            if (c == null || c.spots == null) continue;
            for (HomeApi.CourseSpot cs : c.spots) {
                if (cs != null && cs.spot != null) spots.putIfAbsent(cs.spot.id, cs.spot);
            }
        }
        for (HomeApi.Spot s : spots.values()) {
            if (s.arrival == null || s.arrival.sensors == null) continue;
            for (HomeApi.SpotSensor sensor : s.arrival.sensors) {
                if (sensor == null) continue;
                List<Integer> ids = map.computeIfAbsent(sensor.sensorId, k -> new ArrayList<>());
                if (!ids.contains(s.id)) ids.add(s.id);
            }
        }
        return map;
    }
}
