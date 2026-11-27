package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 방문한 장소와 도착 시각 (#37 방문 시각 기록).
 * 코스 화면의 "1/3 방문", 장소 안내 화면의 방문 표시, 내 투어 화면이 같이 쓴다.
 *
 * - 도착 버튼과 BLE 자동 도착(TourScanner) 모두 markArrived()로 기록한다. 같은 장소는 처음 도착만 남긴다.
 * - 기록은 폰 안에만 저장하고 서버로 보내지 않는다. 앱을 껐다 켜도 남는다. (init()을 부른 뒤부터)
 * - init()을 부르지 않으면(단위 테스트) 메모리에만 둔다.
 */
public final class TourProgress {

    /** 한 장소의 방문 기록 */
    public static final class Visit {
        public final int spotId;
        /** 도착 버튼으로 체크했으면 true, BLE로 자동 인식했으면 false */
        public final boolean manual;
        /** 도착 시각 (Unix 밀리초, 폰 시계) */
        public final long arrivedAt;

        Visit(int spotId, boolean manual, long arrivedAt) {
            this.spotId = spotId;
            this.manual = manual;
            this.arrivedAt = arrivedAt;
        }
    }

    private static final String PREFS = "tour_progress";
    private static final String PREF_VISITS = "visits";
    /** 도착한 순서대로 */
    private static final Map<Integer, Visit> VISITS = new LinkedHashMap<>();
    private static SharedPreferences prefs;
    /** 기록이 지워졌을 때 알릴 곳 (TourScanner: 지금 센서 앞에 있으면 그 장소를 다시 기록) */
    private static Runnable onForget;

    private TourProgress() {}

    /** 기록이 지워질 때(clear, forget) 부를 일을 정한다. TourScanner가 쓴다 */
    public static synchronized void setOnForget(Runnable r) {
        onForget = r;
    }

    /** 화면을 열 때 부른다. 폰에 저장해 둔 기록을 한 번 읽어 오고, 이후 기록은 저장한다 */
    public static synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Visit v : decode(prefs.getString(PREF_VISITS, null))) {
            if (!VISITS.containsKey(v.spotId)) VISITS.put(v.spotId, v);
        }
    }

    public static synchronized boolean isVisited(int spotId) {
        return VISITS.containsKey(spotId);
    }

    /** 도착 버튼으로 체크했으면 true, 센서로 자동 인식했으면 false */
    public static synchronized boolean isManual(int spotId) {
        Visit v = VISITS.get(spotId);
        return v != null && v.manual;
    }

    /** 처음 도착할 때만 지금 시각으로 기록한다. 새로 기록했으면 true */
    public static boolean markArrived(int spotId, boolean manual) {
        return markArrived(spotId, manual, System.currentTimeMillis());
    }

    static synchronized boolean markArrived(int spotId, boolean manual, long arrivedAt) {
        if (VISITS.containsKey(spotId)) return false;
        VISITS.put(spotId, new Visit(spotId, manual, arrivedAt));
        save();
        return true;
    }

    /** 도착한 순서대로의 방문 기록 (복사본) */
    public static synchronized List<Visit> visits() {
        return new ArrayList<>(VISITS.values());
    }

    /** 이 장소의 도착 시각. 아직 안 갔으면 null */
    public static synchronized Long arrivedAt(int spotId) {
        Visit v = VISITS.get(spotId);
        return v == null ? null : v.arrivedAt;
    }

    /** 기록을 모두 지운다 (내 투어의 "기록 지우기") */
    public static synchronized void clear() {
        VISITS.clear();
        save();
        if (onForget != null) onForget.run();
    }

    /** 이 장소들의 기록만 지운다 (내 코스를 새로 걸을 때). 다른 장소 기록은 그대로 */
    public static synchronized void forget(List<Integer> spotIds) {
        if (spotIds == null) return;
        boolean changed = false;
        for (Integer id : spotIds) if (id != null && VISITS.remove(id) != null) changed = true;
        if (!changed) return;
        save();
        if (onForget != null) onForget.run();
    }

    /** 이 장소들 중 이미 도착 기록이 있는 장소 (주어진 순서대로) */
    public static synchronized List<Integer> visitedAmong(List<Integer> spotIds) {
        List<Integer> out = new ArrayList<>();
        if (spotIds == null) return out;
        for (Integer id : spotIds) if (id != null && VISITS.containsKey(id)) out.add(id);
        return out;
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

    private static void save() {
        if (prefs != null) prefs.edit().putString(PREF_VISITS, encode(new ArrayList<>(VISITS.values()))).apply();
    }

    /** 저장 형식: "장소id,수동(1/0),도착시각;..." (Android 없이 테스트할 수 있게 단순한 글자로) */
    static String encode(List<Visit> visits) {
        StringBuilder sb = new StringBuilder();
        for (Visit v : visits) {
            if (sb.length() > 0) sb.append(';');
            sb.append(v.spotId).append(',').append(v.manual ? 1 : 0).append(',').append(v.arrivedAt);
        }
        return sb.toString();
    }

    /** encode()의 반대. 형식이 틀린 항목은 건너뛴다 */
    static List<Visit> decode(String text) {
        List<Visit> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String item : text.split(";")) {
            String[] p = item.split(",");
            if (p.length != 3) continue;
            try {
                out.add(new Visit(Integer.parseInt(p[0].trim()), "1".equals(p[1].trim()), Long.parseLong(p[2].trim())));
            } catch (NumberFormatException ignored) {
                // 건너뜀
            }
        }
        return out;
    }

    /** 테스트용: 처음 상태로 */
    static synchronized void resetForTest() {
        VISITS.clear();
        prefs = null;
        onForget = null;
    }
}
