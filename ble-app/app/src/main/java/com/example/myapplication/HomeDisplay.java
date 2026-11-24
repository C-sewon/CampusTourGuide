package com.example.myapplication;

import java.util.Locale;

/**
 * 홈 화면 추천 코스 카드의 문구를 만든다.
 * 값은 서버 GET /api/courses/recommend 응답을 그대로 받는다.
 * (condition: hot | cold | comfortable | unknown, temperature: 코스 장소들의 평균 기온)
 * 화면과 분리해 두어 단위 테스트로 확인할 수 있다.
 */
public final class HomeDisplay {

    /** 추천 이유 줄의 색 구분 */
    public enum Tone { WARM, GOOD, NEUTRAL }

    private HomeDisplay() {}

    /** 기온 문구. 정수면 "31°C", 소수면 "21.5°C". */
    public static String temperature(double celsius) {
        double rounded = Math.round(celsius * 10) / 10.0;
        if (rounded == Math.rint(rounded)) {
            return String.format(Locale.ROOT, "%d°C", (long) rounded);
        }
        return String.format(Locale.ROOT, "%.1f°C", rounded);
    }

    /** 기온을 모를 때. 서버 규칙(모르면 가장 긴 코스)과 같다 */
    private static final String UNKNOWN_REASON = "지금은 기온을 알 수 없어 긴 코스를 추천해요";

    /**
     * 추천 이유 한 줄. 기온을 모르면 기온 없이 안내한다.
     * 덥거나 추우면 서버가 가장 짧은 "핵심 코스"를 추천한다. (#22에서 이름을 "짧은 코스"에서 바꿈)
     */
    public static String reason(String condition, Double celsius) {
        if (celsius == null || condition == null || "unknown".equals(condition)) {
            return UNKNOWN_REASON;
        }
        String now = "지금 " + temperature(celsius) + " · ";
        switch (condition) {
            case "hot":
                return now + "더운 날엔 핵심 코스를 추천해요";
            case "cold":
                return now + "추운 날엔 핵심 코스를 추천해요";
            case "comfortable":
                return now + "걷기 좋은 날이라 긴 코스를 추천해요";
            default:
                return UNKNOWN_REASON;
        }
    }

    /** 서버 없이 추천할 때. serverDown 이면 서버 주소는 있는데 연결이 안 된 것 */
    public static String offlineReason(boolean serverDown) {
        return serverDown ? "서버에 연결할 수 없어 기온 없이 추천해요" : UNKNOWN_REASON;
    }

    /** 추천 이유 줄의 색. 덥거나 추우면 주의(주황), 쾌적하면 초록, 모르면 회색. */
    public static Tone tone(String condition) {
        if ("hot".equals(condition) || "cold".equals(condition)) return Tone.WARM;
        if ("comfortable".equals(condition)) return Tone.GOOD;
        return Tone.NEUTRAL;
    }

    /** 지도 센서 점의 색 구분 */
    public enum SensorTone { HOT, COLD, COMFORTABLE, NO_VALUE }

    /** 서버 추천과 같은 기준: 27℃ 이상 덥다, 5℃ 이하 춥다 (config HOT_MIN_CELSIUS, COLD_MAX_CELSIUS) */
    public static final double HOT_MIN = 27;
    public static final double COLD_MAX = 5;

    /** 센서 온도로 지도 점 색을 고른다. 값이 없으면 회색. */
    public static SensorTone sensorTone(Double celsius) {
        if (celsius == null) return SensorTone.NO_VALUE;
        if (celsius >= HOT_MIN) return SensorTone.HOT;
        if (celsius <= COLD_MAX) return SensorTone.COLD;
        return SensorTone.COMFORTABLE;
    }

    /** 지도 센서 점을 눌렀을 때 문구. 예: "센서 1 · 도서관 뒤편 · 22°C" */
    public static String sensorLabel(int sensorId, String location, Double celsius) {
        String value = celsius == null ? "지금은 값을 받을 수 없어요" : temperature(celsius);
        return "센서 " + sensorId + " · " + location + " · " + value;
    }

    /**
     * 서버 측정값으로 만든 센서 점 문구.
     * hasReading 이 false 면 값이 없는 것, stale 이면 오래된 값, suspect 면 고장 의심 값이다.
     */
    public static String sensorLabel(int sensorId, String location, boolean hasReading,
                                     double celsius, int ageMinutes,
                                     boolean stale, boolean suspect) {
        String head = "센서 " + sensorId + " · " + location + " · ";
        if (!hasReading) return head + "아직 값이 없어요";
        if (suspect) return head + "값을 점검하고 있어요";
        if (stale) return head + "마지막 값 " + temperature(celsius) + " (" + ago(ageMinutes) + ")";
        return head + temperature(celsius) + " · " + ago(ageMinutes);
    }

    /** 측정 후 지난 시간. 예: "방금", "3분 전", "2시간 전", "1일 전" */
    public static String ago(int minutes) {
        if (minutes < 1) return "방금";
        if (minutes < 60) return minutes + "분 전";
        if (minutes < 60 * 24) return (minutes / 60) + "시간 전";
        return (minutes / (60 * 24)) + "일 전";
    }

    /**
     * 카드 아래 요약. 예: "약 12분 · 장소 2곳". 장소 수를 모르면 시간만.
     * 코스 시간은 이동 + 관람 합계라 "도보"라고 하지 않는다. (#52 13번)
     */
    public static String meta(int totalMinutes, int spotCount) {
        if (spotCount <= 0) return "약 " + totalMinutes + "분";
        return "약 " + totalMinutes + "분 · 장소 " + spotCount + "곳";
    }

    /** 사용자가 입력한 서버 주소를 Retrofit 기본 주소 형태로 맞춘다. 예: "192.168.0.5:8000" → "http://192.168.0.5:8000/" */
    public static String normalizeBaseUrl(String input) {
        if (input == null) return "";
        String url = input.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        if (!url.endsWith("/")) url = url + "/";
        return url;
    }
}
