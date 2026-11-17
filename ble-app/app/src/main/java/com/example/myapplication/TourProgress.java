package com.example.myapplication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이번 실행 동안 방문한 장소 (장소 id → 도착 버튼으로 체크했는지).
 * 코스 화면의 "1/3 방문"과 장소 안내 화면의 방문 표시가 같이 쓴다.
 * 앱을 끄면 사라진다. 내 투어 화면을 만들 때 저장하도록 바꾼다.
 */
public final class TourProgress {

    private static final Map<Integer, Boolean> ARRIVED = new LinkedHashMap<>();

    private TourProgress() {}

    public static boolean isVisited(int spotId) {
        return ARRIVED.containsKey(spotId);
    }

    /** 도착 버튼으로 체크했으면 true, 센서로 자동 인식했으면 false */
    public static boolean isManual(int spotId) {
        return Boolean.TRUE.equals(ARRIVED.get(spotId));
    }

    /** 처음 도착할 때만 기록한다. 새로 기록했으면 true */
    public static boolean markArrived(int spotId, boolean manual) {
        if (ARRIVED.containsKey(spotId)) return false;
        ARRIVED.put(spotId, manual);
        return true;
    }

    public static int countVisited(List<HomeApi.CourseSpot> stops) {
        int n = 0;
        if (stops == null) return 0;
        for (HomeApi.CourseSpot s : stops) if (s.spot != null && isVisited(s.spot.id)) n++;
        return n;
    }

    /** 아직 안 간 첫 장소의 순서. 모두 갔으면 처음부터 */
    public static int firstUnvisited(List<HomeApi.CourseSpot> stops) {
        if (stops == null) return 0;
        for (int i = 0; i < stops.size(); i++) {
            HomeApi.Spot s = stops.get(i).spot;
            if (s != null && !isVisited(s.id)) return i;
        }
        return 0;
    }
}
