package com.example.myapplication;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CourseDisplayTest {

    @Test
    public void courseMeta() {
        assertEquals("총 25분 · 장소 3곳", CourseDisplay.courseMeta(25, 3));
    }

    @Test
    public void progress() {
        assertEquals("1/3 방문", CourseDisplay.progress(1, 3));
    }

    @Test
    public void badge() {
        assertEquals("더운 날 추천", CourseDisplay.badge("hot"));
        assertEquals("추운 날 추천", CourseDisplay.badge("cold"));
        assertEquals("걷기 좋은 날 추천", CourseDisplay.badge("comfortable"));
        assertEquals("추천", CourseDisplay.badge("unknown"));
    }

    @Test
    public void startLabel() {
        assertEquals("이 코스 시작하기", CourseDisplay.startLabel(0, 3));
        assertEquals("이 코스 이어서 걷기", CourseDisplay.startLabel(1, 3));
        assertEquals("이 코스 다시 걷기", CourseDisplay.startLabel(3, 3));
    }

    @Test
    public void stopsTitle() {
        assertEquals("핵심 코스 · 장소 순서", CourseDisplay.stopsTitle("핵심 코스"));
    }
}
