package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class MyTourDisplayTest {

    private static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");
    private static final long MIN = 60_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static TourProgress.Visit visit(int id, long at) {
        return new TourProgress.Visit(id, false, at);
    }

    @Test
    public void headline() {
        assertEquals("아직 방문한 곳이 없어요", MyTourDisplay.headline(0));
        assertEquals("4곳 방문했어요", MyTourDisplay.headline(4));
        assertEquals("코스를 골라 투어를 시작해 보세요", MyTourDisplay.headlineSub(0));
        assertEquals("도착한 곳은 자동으로 기록돼요", MyTourDisplay.headlineSub(2));
    }

    @Test
    public void clock_inLocalTime() {
        // 2026-11-26 05:05:00 UTC = 14:05 KST
        assertEquals("14:05", MyTourDisplay.clock(1795669500000L, KST));
    }

    @Test
    public void duration() {
        assertEquals("1분 미만", MyTourDisplay.duration(30_000L));
        assertEquals("24분", MyTourDisplay.duration(24 * MIN + 59_000L));
        assertEquals("1시간", MyTourDisplay.duration(60 * MIN));
        assertEquals("1시간 5분", MyTourDisplay.duration(65 * MIN));
        assertEquals("1분 미만", MyTourDisplay.duration(-5_000L));
    }

    @Test
    public void elapsed_firstToLast() {
        List<TourProgress.Visit> visits = Arrays.asList(visit(7, 30 * MIN), visit(6, 0), visit(10, 12 * MIN));
        assertEquals(30 * MIN, MyTourDisplay.elapsed(visits, UTC));
        assertEquals(0, MyTourDisplay.elapsed(Collections.singletonList(visit(6, 0)), UTC));
        assertEquals(0, MyTourDisplay.elapsed(Collections.emptyList(), UTC));
    }

    @Test
    public void sorted_byArrivalTime() {
        List<TourProgress.Visit> s = MyTourDisplay.sorted(Arrays.asList(visit(7, 30 * MIN), visit(6, 0)));
        assertEquals(6, s.get(0).spotId);
        assertEquals(7, s.get(1).spotId);
    }

    @Test
    public void gap() {
        assertEquals("첫 방문", MyTourDisplay.gap(null, 5 * MIN, UTC));
        assertEquals("앞 장소에서 6분", MyTourDisplay.gap(0L, 6 * MIN, UTC));
        assertEquals("수동 도착 체크", MyTourDisplay.manualChip());
    }

    @Test
    public void manyDays_dateRowsAndNoOvernightGap() {
        // 어제 15:00, 15:20 / 오늘 09:00, 09:10 (서울 시간). 통합 테스트가 이틀이라 생길 수 있다 (PR #63 리뷰 2)
        TimeZone seoul = TimeZone.getTimeZone("Asia/Seoul");
        long day1 = 1795791600000L;          // 2026-11-28 00:00 KST
        long a = day1 + 15 * 60 * MIN, b = a + 20 * MIN;
        long c = day1 + 24 * 60 * MIN + 9 * 60 * MIN, d = c + 10 * MIN;
        List<TourProgress.Visit> visits = Arrays.asList(visit(6, a), visit(7, b), visit(4, c), visit(10, d));

        assertTrue(MyTourDisplay.manyDays(visits, seoul));
        assertFalse(MyTourDisplay.manyDays(visits.subList(0, 2), seoul));
        assertEquals("11월 28일 (토)", MyTourDisplay.dateLabel(a, seoul));
        assertEquals("11월 29일 (일)", MyTourDisplay.dateLabel(c, seoul));
        assertEquals("첫 방문", MyTourDisplay.gap(b, c, seoul));         // 18시간이 아니라 그날 첫 방문
        assertEquals("앞 장소에서 10분", MyTourDisplay.gap(c, d, seoul));
        assertEquals(30 * MIN, MyTourDisplay.elapsed(visits, seoul));   // 20분 + 10분, 밤사이는 세지 않음
    }

    @Test
    public void courseProgressAndResume() {
        assertNull(MyTourDisplay.courseProgress("정문 코스", 0, 7));
        assertEquals("정문 코스 3/7곳 방문 중", MyTourDisplay.courseProgress("정문 코스", 3, 7));
        assertEquals("정문 코스 7곳을 모두 걸었어요", MyTourDisplay.courseProgress("정문 코스", 7, 7));

        assertFalse(MyTourDisplay.canResume(0, 7));  // 아직 시작 전
        assertTrue(MyTourDisplay.canResume(3, 7));
        assertFalse(MyTourDisplay.canResume(7, 7));  // 다 걸음
        assertEquals("정문 코스 이어서 걷기 · 3/7곳", MyTourDisplay.resumeLabel("정문 코스", 3, 7));
        assertEquals("2/6장", MyTourDisplay.cards(2, 6));
    }
}
