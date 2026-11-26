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

    @Test
    public void myCourseTexts() {
        assertEquals("원하는 장소를 골라 순서대로 걸어요", CourseDisplay.myCourseEntryMeta(0));
        assertEquals("지난번에 고른 3곳 · 눌러서 고치거나 시작해요", CourseDisplay.myCourseEntryMeta(3));
        assertEquals("아직 고른 곳이 없어요 · 2곳 이상 골라 주세요", CourseDisplay.myCourseSummary(0, 2));
        assertEquals("1곳 골랐어요 · 2곳 이상 골라 주세요", CourseDisplay.myCourseSummary(1, 2));
        assertEquals("3곳 골랐어요 · 누른 순서대로 걸어요", CourseDisplay.myCourseSummary(3, 2));
        assertEquals("대학본부, 솔못은 이미 도착 기록이 있어요. 이 장소들의 기록을 지우고 새로 시작할까요?",
                CourseDisplay.alreadyVisitedMessage(java.util.Arrays.asList("대학본부", "솔못")));
        assertEquals("대학본부는 이미 도착 기록이 있어요. 이 장소들의 기록을 지우고 새로 시작할까요?",
                CourseDisplay.alreadyVisitedMessage(java.util.Collections.singletonList("대학본부")));
        assertEquals("는", CourseDisplay.topic("양성재"));
        assertEquals("은", CourseDisplay.topic("솔못"));
        assertEquals("는", CourseDisplay.topic("정문 코스"));
        assertEquals("은(는)", CourseDisplay.topic("Gate 1"));
    }
}
