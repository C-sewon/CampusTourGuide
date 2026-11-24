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
}
