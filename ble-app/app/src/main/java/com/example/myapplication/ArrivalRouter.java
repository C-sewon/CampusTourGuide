package com.example.myapplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "센서 N 도착"을 장소로 바꾼다.
 *
 * 서버 코스 데이터의 장소마다 arrival.sensors(이 장소에 설치된 센서)가 있다.
 * 센서가 감지되면 그 센서가 들어 있는 모든 장소를 도착으로 본다. 지금 안내 중인 장소인지와는 상관없다. (#37)
 * 예: 센서 4 또는 5 → 테니스장, 센서 1 → 도서관 뒤편 (어느 코스를 걷고 있든)
 *
 * 화면이 코스 목록을 받을 때마다 update()로 알려 준다. Android 기능을 쓰지 않아 PC에서 테스트할 수 있다.
 *
 * 코스 목록을 아직 한 번도 받지 못했을 때 (연결이 비어 있을 때)
 * - 앱이 장소 안내 화면부터 다시 열리면 홈을 거치지 않아서, 코스 목록을 받기 전에 센서에 도착할 수 있다.
 * - 이때 도착한 센서는 버리지 않고 보관해 두었다가, update()로 연결이 처음 채워질 때 Listener로 돌려준다.
 *   그러면 TourScanner가 그 센서의 도착을 다시 기록한다. (떠났다가 다시 오지 않아도 기록됨)
 */
public final class ArrivalRouter {

    /** 연결이 비어 있을 때 도착해 보관해 둔 센서를, 연결이 채워진 뒤 돌려받는다 */
    public interface Listener {
        void onHeldSensors(List<Integer> sensorIds);
    }

    private static Map<Integer, List<Integer>> sensorToSpots = Collections.emptyMap();
    private static final Set<Integer> held = new LinkedHashSet<>();
    private static Listener listener;

    private ArrivalRouter() {}

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    /** 코스 목록을 받으면 부른다 (서버 응답, 폰 저장본, 기본 목록). null이나 빈 목록이면 이전 값을 지우지 않는다 */
    public static void update(List<HomeApi.Course> courses) {
        Map<Integer, List<Integer>> built = build(courses);
        List<Integer> ready;
        Listener l;
        synchronized (ArrivalRouter.class) {
            if (built.isEmpty()) return;
            sensorToSpots = built;
            if (held.isEmpty()) return;
            ready = new ArrayList<>(held);
            held.clear();
            l = listener;
        }
        // 잠금 밖에서 알린다 (Listener가 spotIdsForSensor를 다시 부르기 때문)
        if (l != null) l.onHeldSensors(ready);
    }

    /** 이 센서가 설치된 장소 id 목록. 모르는 센서면 빈 목록 */
    public static synchronized List<Integer> spotIdsForSensor(int sensorId) {
        List<Integer> ids = sensorToSpots.get(sensorId);
        return ids == null ? Collections.emptyList() : ids;
    }

    /**
     * 도착한 센서의 장소 id 목록. 연결이 아직 비어 있으면 센서를 보관하고 빈 목록을 준다.
     * 보관한 센서는 연결이 채워질 때 Listener로 돌려준다.
     */
    public static synchronized List<Integer> spotIdsOrHold(int sensorId) {
        if (sensorToSpots.isEmpty()) {
            held.add(sensorId);
            return Collections.emptyList();
        }
        return spotIdsForSensor(sensorId);
    }

    /** 테스트용: 처음 상태로 되돌린다 */
    static synchronized void reset() {
        sensorToSpots = Collections.emptyMap();
        held.clear();
        listener = null;
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
