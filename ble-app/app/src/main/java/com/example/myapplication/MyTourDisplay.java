package com.example.myapplication;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 내 투어 화면 문구 (#37, RF-FX-02). 방문 기록(TourProgress)으로 도착 시각, 장소 사이 시간, 총 경과 시간을 만든다.
 * 화면과 분리해 두어 단위 테스트로 확인할 수 있다.
 */
public final class MyTourDisplay {

    private MyTourDisplay() {}

    /** 도착한 시각 순서로 정렬한 복사본 */
    public static List<TourProgress.Visit> sorted(List<TourProgress.Visit> visits) {
        List<TourProgress.Visit> out = new ArrayList<>(visits);
        Collections.sort(out, (a, b) -> Long.compare(a.arrivedAt, b.arrivedAt));
        return out;
    }

    /** 위 카드 제목 */
    public static String headline(int visited) {
        return visited <= 0 ? "아직 방문한 곳이 없어요" : visited + "곳 방문했어요";
    }

    /** 위 카드 설명 */
    public static String headlineSub(int visited) {
        return visited <= 0 ? "코스를 골라 투어를 시작해 보세요" : "도착한 곳은 자동으로 기록돼요";
    }

    /** 도착 시각. 예: "14:05" */
    public static String clock(long millis, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        f.setTimeZone(zone);
        return f.format(new Date(millis));
    }

    /** 걸린 시간. 예: "1분 미만", "24분", "1시간", "1시간 5분" */
    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 1) return "1분 미만";
        if (minutes < 60) return minutes + "분";
        long h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }

    /** 첫 도착부터 마지막 도착까지 걸린 시간 (총 경과 시간). 기록이 없으면 0 */
    public static long elapsed(List<TourProgress.Visit> visits) {
        if (visits == null || visits.size() < 2) return 0;
        List<TourProgress.Visit> s = sorted(visits);
        return s.get(s.size() - 1).arrivedAt - s.get(0).arrivedAt;
    }

    /** 발자국 한 줄의 앞 장소와의 시간. 첫 장소면 "첫 방문" */
    public static String gap(Long previousArrivedAt, long arrivedAt) {
        if (previousArrivedAt == null) return "첫 방문";
        return "앞 장소에서 " + duration(arrivedAt - previousArrivedAt);
    }

    /** 도착 버튼으로 체크한 곳에 붙는 표시 */
    public static String manualChip() {
        return "수동 도착 체크";
    }
}
