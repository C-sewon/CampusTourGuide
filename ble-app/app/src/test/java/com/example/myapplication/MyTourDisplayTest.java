package com.example.myapplication;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class MyTourDisplayTest {

    private static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");
    private static final long MIN = 60_000L;

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
        assertEquals(30 * MIN, MyTourDisplay.elapsed(visits));
        assertEquals(0, MyTourDisplay.elapsed(Collections.singletonList(visit(6, 0))));
        assertEquals(0, MyTourDisplay.elapsed(Collections.emptyList()));
    }

    @Test
    public void sorted_byArrivalTime() {
        List<TourProgress.Visit> s = MyTourDisplay.sorted(Arrays.asList(visit(7, 30 * MIN), visit(6, 0)));
        assertEquals(6, s.get(0).spotId);
        assertEquals(7, s.get(1).spotId);
    }

    @Test
    public void gap() {
        assertEquals("첫 방문", MyTourDisplay.gap(null, 5 * MIN));
        assertEquals("앞 장소에서 6분", MyTourDisplay.gap(0L, 6 * MIN));
        assertEquals("수동 도착 체크", MyTourDisplay.manualChip());
    }
}
