package com.example.myapplication;

import java.util.Locale;

/**
 * 장소 안내 화면 문구 (프로토타입 campus-on-prototype 기준).
 * 값은 서버 GET /api/courses 의 장소(SpotOut) 응답을 그대로 받는다.
 * 화면과 분리해 두어 단위 테스트로 확인할 수 있다.
 */
public final class SpotDisplay {

    /** 맨 위 도착 배너 모양 */
    public enum Banner {
        /** 센서 장소, 아직 도착 전: 점선 테두리 + 블루투스 */
        WAITING,
        /** 센서 없는 장소, 아직 도착 전: 진한 테두리 + 손 */
        MANUAL_NEEDED,
        /** 도착함: 민트 바탕 + 체크 */
        ARRIVED
    }

    private SpotDisplay() {}

    /** 코스 진행. 예: "01 / 04" (index 는 0부터) */
    public static String progress(int index, int total) {
        return String.format(Locale.ROOT, "%02d / %02d", index + 1, total);
    }

    /** 배너 모양. method 는 ble_auto(센서로 자동) 또는 manual(도착 버튼) */
    public static Banner banner(String method, boolean arrived) {
        if (arrived) return Banner.ARRIVED;
        return "ble_auto".equals(method) ? Banner.WAITING : Banner.MANUAL_NEEDED;
    }

    public static String bannerTitle(Banner banner, boolean manualArrival) {
        switch (banner) {
            case WAITING: return "장소에 가까워지고 있어요";
            case MANUAL_NEEDED: return "이 장소는 '도착' 버튼으로 체크해요";
            default: return manualArrival ? "도착 체크했어요" : "도착했어요!";
        }
    }

    public static String bannerSub(Banner banner, boolean manualArrival) {
        switch (banner) {
            case WAITING: return "가까이 가면 도착을 자동으로 인식해요.";
            case MANUAL_NEEDED: return "도착 감지 센서가 없는 장소예요. 도착하면 아래 버튼을 눌러 주세요.";
            default: return manualArrival ? "'도착' 버튼으로 방문을 기록했어요." : "BLE 센서가 장소를 인식했습니다.";
        }
    }

    /**
     * 온도 아래 출처 줄. 장소 센서면 "이 장소 센서 · 3분 전 측정",
     * 가까운 센서 값을 빌렸으면 "약 56m 떨어진 센서 기준 · 3분 전 측정" (거리를 모르면 "가까운 센서 기준").
     */
    public static String source(boolean onSite, Integer distanceM, int ageMinutes) {
        String where;
        if (onSite) where = "이 장소 센서";
        else if (distanceM != null) where = "약 " + distanceM + "m 떨어진 센서 기준";
        else where = "가까운 센서 기준";
        return where + " · " + measured(ageMinutes);
    }

    /** 오래된 값의 출처 줄. 예: "35분 전 측정 · 최신 값이 아닐 수 있어요" */
    public static String staleSource(int ageMinutes) {
        return measured(ageMinutes) + " · 최신 값이 아닐 수 있어요";
    }

    /** 예: "3분 전 측정", "방금 측정" */
    public static String measured(int ageMinutes) {
        return HomeDisplay.ago(ageMinutes) + " 측정";
    }

    /**
     * 다음 장소까지. 예: "약 4분".
     * 서버의 지점별 시간은 이동 + 관람 합계라 "도보"라고 하지 않는다. (#52 13번)
     */
    public static String walk(int minutes) {
        return "약 " + minutes + "분";
    }

    /**
     * 내 위치에서 가야 할 장소까지 남은 거리. 예: "남은 거리 약 120m", "남은 거리 약 1.2km".
     * 20m 안이면 "거의 다 왔어요" (GPS 오차가 그 정도라 더 자세히 말하지 않는다).
     */
    public static String distance(double meters) {
        if (meters < 20) return "거의 다 왔어요";
        if (meters < 1000) return "남은 거리 약 " + (Math.round(meters / 10.0) * 10) + "m";
        return String.format(Locale.ROOT, "남은 거리 약 %.1fkm", meters / 1000.0);
    }

    /**
     * 걷는 길 경로가 있을 때 남은 거리와 도보 시간. 예: "남은 거리 약 400m · 도보 약 5분".
     * 20m 안이면 "거의 다 왔어요".
     */
    public static String routeDistance(double meters, double durationSec) {
        if (meters < 20) return "거의 다 왔어요";
        long minutes = Math.max(1, Math.round(durationSec / 60.0));
        return distance(meters) + " · 도보 약 " + minutes + "분";
    }

    /** 다음 버튼. 마지막 장소면 투어를 마친다 */
    public static String nextLabel(boolean isLast) {
        return isLast ? "투어 마치기" : "다음 장소로";
    }

    /** 도착 버튼 */
    public static String arriveLabel(boolean arrived) {
        return arrived ? "도착 완료" : "도착";
    }
}
