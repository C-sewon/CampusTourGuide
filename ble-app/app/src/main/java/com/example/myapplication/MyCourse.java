package com.example.myapplication;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 코스 (#37 자유 탐색). 장소 12곳 중에서 누르는 순서대로 고른 장소 목록이다.
 * - 한 번 더 누르면 빠지고, 뒤의 번호가 하나씩 당겨진다.
 * - 걷는 중에 순서를 바꾸는 기능은 없다. (#37 범위 축소)
 * - 고른 순서는 폰에 저장해서, 장소 안내 화면이 다시 열려도 같은 코스로 이어진다.
 * 장소 안내 화면은 서버 코스처럼 COURSE_ID 로 연다.
 */
public final class MyCourse {

    /** 장소 안내 화면에 넘기는 내 코스의 코스 번호 (서버 코스 번호는 1부터라 겹치지 않음) */
    public static final int COURSE_ID = -1;
    public static final String NAME = "내 코스";
    /** 이만큼 골라야 시작할 수 있다 */
    public static final int MIN_SPOTS = 2;

    private static final String PREFS = "my_course";
    private static final String PREF_SPOTS = "spot_ids";

    private final List<Integer> order = new ArrayList<>();

    public MyCourse() {}

    public MyCourse(List<Integer> spotIds) {
        for (Integer id : spotIds) if (id != null && !order.contains(id)) order.add(id);
    }

    /** 누르면 고르고, 이미 골랐으면 뺀다. 누른 뒤 골라져 있으면 true */
    public boolean toggle(int spotId) {
        if (order.remove(Integer.valueOf(spotId))) return false;
        order.add(spotId);
        return true;
    }

    /** 이 장소의 순서 번호 (1부터). 고르지 않았으면 0 */
    public int numberOf(int spotId) {
        return order.indexOf(spotId) + 1;
    }

    public int size() {
        return order.size();
    }

    public boolean canStart() {
        return order.size() >= MIN_SPOTS;
    }

    public List<Integer> spotIds() {
        return Collections.unmodifiableList(new ArrayList<>(order));
    }

    // ---------- 코스 목록에서 장소 고르기 ----------

    /** 코스 목록에 들어 있는 장소를 한 번씩, 장소 번호 순서로. (서버 장소 순서와 같다) */
    public static List<HomeApi.Spot> allSpots(List<HomeApi.Course> courses) {
        Map<Integer, HomeApi.Spot> byId = new LinkedHashMap<>();
        if (courses != null) {
            for (HomeApi.Course c : courses) {
                if (c.spots == null) continue;
                for (HomeApi.CourseSpot cs : c.spots) {
                    if (cs.spot != null && !byId.containsKey(cs.spot.id)) byId.put(cs.spot.id, cs.spot);
                }
            }
        }
        List<HomeApi.Spot> spots = new ArrayList<>(byId.values());
        Collections.sort(spots, (a, b) -> Integer.compare(a.id, b.id));
        return spots;
    }

    /**
     * 고른 순서대로 장소 안내 화면이 쓸 코스를 만든다. 목록에 없는 장소는 뺀다. 고른 장소가 없으면 null.
     * 지점별 시간(minutes)은 서버 코스처럼 걷는 길로 잰 값이 없어서 0이다. (화면은 0이면 시간을 보여 주지 않음)
     */
    public static HomeApi.Course build(List<HomeApi.Course> courses, List<Integer> spotIds) {
        Map<Integer, HomeApi.Spot> byId = new LinkedHashMap<>();
        for (HomeApi.Spot s : allSpots(courses)) byId.put(s.id, s);
        HomeApi.Course course = new HomeApi.Course();
        course.id = COURSE_ID;
        course.name = NAME;
        course.description = "";
        course.spots = new ArrayList<>();
        int position = 1;
        if (spotIds != null) {
            for (Integer id : spotIds) {
                HomeApi.Spot spot = id == null ? null : byId.get(id);
                if (spot == null) continue;
                HomeApi.CourseSpot cs = new HomeApi.CourseSpot();
                cs.position = position++;
                cs.minutes = 0;
                cs.spot = spot;
                course.spots.add(cs);
            }
        }
        return course.spots.isEmpty() ? null : course;
    }

    // ---------- 폰에 저장 ----------

    public static void save(Context context, List<Integer> spotIds) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_SPOTS, encode(spotIds)).apply();
    }

    public static List<Integer> load(Context context) {
        return decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SPOTS, null));
    }

    static String encode(List<Integer> spotIds) {
        StringBuilder sb = new StringBuilder();
        if (spotIds != null) {
            for (Integer id : spotIds) {
                if (id == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.toString();
    }

    static List<Integer> decode(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String p : text.split(",")) {
            try {
                int id = Integer.parseInt(p.trim());
                if (!out.contains(id)) out.add(id);
            } catch (NumberFormatException ignored) {
                // 건너뜀
            }
        }
        return out;
    }
}
