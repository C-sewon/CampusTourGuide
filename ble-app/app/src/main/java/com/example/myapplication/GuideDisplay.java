package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

/**
 * 가이드 화면 문구 (#64). 장소 이름은 코스 데이터(TourData)에서 읽어서, 서버 데이터가 바뀌어도 맞게 보인다.
 * 화면과 분리해 단위 테스트로 확인한다. (GuideDisplayTest)
 */
public final class GuideDisplay {

    private GuideDisplay() {}

    /** 센서로 자동 도착하는 장소 (arrival.method = ble_auto), 장소 번호 순서 */
    public static List<String> autoSpots(List<HomeApi.Course> courses) {
        List<String> out = new ArrayList<>();
        for (HomeApi.Spot s : MyCourse.allSpots(courses)) if (isAuto(s)) out.add(s.name);
        return out;
    }

    /** '도착' 버튼으로 체크하는 장소 수 */
    public static int manualCount(List<HomeApi.Course> courses) {
        int n = 0;
        for (HomeApi.Spot s : MyCourse.allSpots(courses)) if (!isAuto(s)) n++;
        return n;
    }

    /** 카드 장소 이름 (장소 번호 순서) */
    public static List<String> cardSpots(List<HomeApi.Course> courses) {
        List<String> out = new ArrayList<>();
        for (HomeApi.Spot s : TourCards.cardSpots(courses)) out.add(s.name);
        return out;
    }

    /** 핵심 코스 장소 이름 (코스 순서) */
    public static List<String> coreSpots(List<HomeApi.Course> courses) {
        List<String> out = new ArrayList<>();
        for (HomeApi.Spot s : TourCards.coreSpots(courses)) out.add(s.name);
        return out;
    }

    /** 예: "도서관 뒤편 · 솔못 · 사회과학대 입구". 없으면 빈 글자 */
    public static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (n == null || n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(n);
        }
        return sb.toString();
    }

    private static boolean isAuto(HomeApi.Spot s) {
        return s.arrival != null && "ble_auto".equals(s.arrival.method);
    }
}
