package com.example.myapplication;

/**
 * 코스 둘러보기 화면 문구. 값은 서버 GET /api/courses, /api/courses/recommend 응답을 그대로 받는다.
 * 화면과 분리해 두어 단위 테스트로 확인할 수 있다.
 */
public final class CourseDisplay {

    private CourseDisplay() {}

    /** 코스 카드 요약. 예: "총 25분 · 장소 3곳" */
    public static String courseMeta(int totalMinutes, int spotCount) {
        return "총 " + totalMinutes + "분 · 장소 " + spotCount + "곳";
    }

    /** 방문 진행. 예: "1/3 방문" */
    public static String progress(int visited, int total) {
        return visited + "/" + total + " 방문";
    }

    /** 추천 코스에 붙는 표시. condition 은 hot | cold | comfortable | unknown */
    public static String badge(String condition) {
        if ("hot".equals(condition)) return "더운 날 추천";
        if ("cold".equals(condition)) return "추운 날 추천";
        if ("comfortable".equals(condition)) return "걷기 좋은 날 추천";
        return "추천";
    }

    /** 아래 버튼. 안 갔으면 시작, 일부 갔으면 이어서, 다 갔으면 다시 */
    public static String startLabel(int visited, int total) {
        if (visited == 0) return "이 코스 시작하기";
        if (visited >= total) return "이 코스 다시 걷기";
        return "이 코스 이어서 걷기";
    }

    /** 장소 순서 목록 제목. 예: "핵심 코스 · 장소 순서" */
    public static String stopsTitle(String courseName) {
        return courseName + " · 장소 순서";
    }

    // ---------- 내 코스 만들기 (#37) ----------

    /** 코스 목록 아래 "내 코스 만들기" 카드의 설명. 고른 적이 있으면 그 수를 알려 준다 */
    public static String myCourseEntryMeta(int savedCount) {
        if (savedCount <= 0) return "원하는 장소를 골라 순서대로 걸어요";
        // 3곳(MyCourse.MIN_SPOTS)이 안 되면 바로 시작할 수 없어서 더 고르라고 한다
        if (savedCount < MyCourse.MIN_SPOTS) return "지난번에 고른 " + savedCount + "곳 · " + MyCourse.MIN_SPOTS + "곳 이상 골라 주세요";
        return "지난번에 고른 " + savedCount + "곳 · 눌러서 고치거나 시작해요";
    }

    /** 내 코스 화면 아래 안내. 예: "3곳 골랐어요 · 누른 순서대로 걸어요" */
    public static String myCourseSummary(int selected, int minSpots) {
        if (selected <= 0) return "아직 고른 곳이 없어요 · " + minSpots + "곳 이상 골라 주세요";
        if (selected < minSpots) return selected + "곳 골랐어요 · " + minSpots + "곳 이상 골라 주세요";
        return selected + "곳 골랐어요 · 누른 순서대로 걸어요";
    }

    /** 내 코스를 시작할 때 이미 간 장소가 있으면 묻는 문구. 예: "대학본부, 솔못은 이미 도착 기록이 있어요. …" */
    public static String alreadyVisitedMessage(java.util.List<String> names) {
        String joined = String.join(", ", names);
        return joined + topic(joined) + " 이미 도착 기록이 있어요. "
                + "이 장소들의 기록을 지우고 새로 시작할까요?";
    }

    /** 마지막 글자에 받침이 있으면 "은", 없으면 "는". 한글이 아니면 "은(는)" */
    static String topic(String word) {
        if (word == null || word.isEmpty()) return "은(는)";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return "은(는)";
        return (last - 0xAC00) % 28 == 0 ? "는" : "은";
    }

    /** 도착하면 카드가 뜨는 장소에 붙는 표시 (has_card) */
    public static String cardChip() {
        return "카드";
    }
}
