package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class MyCourseTest {

    private static List<HomeApi.Course> defaults() throws IOException {
        return TourData.parse(new String(Files.readAllBytes(
                Paths.get("src/main/assets/" + TourData.DEFAULT_ASSET)), StandardCharsets.UTF_8));
    }

    @Test
    public void toggle_numbersInTapOrder() {
        MyCourse c = new MyCourse();
        assertTrue(c.toggle(7));
        assertTrue(c.toggle(1));
        assertTrue(c.toggle(10));
        assertEquals(1, c.numberOf(7));
        assertEquals(3, c.numberOf(10));
        assertEquals(0, c.numberOf(5));

        // 한 번 더 누르면 빠지고 뒤 번호가 당겨진다
        assertFalse(c.toggle(1));
        assertEquals(2, c.numberOf(10));
        assertEquals(Arrays.asList(7, 10), c.spotIds());
    }

    @Test
    public void canStart_needsMinSpots() {
        MyCourse c = new MyCourse();
        c.toggle(7);
        assertFalse(c.canStart());
        c.toggle(1);
        assertFalse(c.canStart());  // 3곳 이상 (RF-FX-01)
        c.toggle(3);
        assertTrue(c.canStart());
    }

    @Test
    public void allSpots_twelveUniqueById() throws IOException {
        List<HomeApi.Spot> spots = MyCourse.allSpots(defaults());
        assertEquals(12, spots.size());
        for (int i = 1; i < spots.size(); i++) assertTrue(spots.get(i - 1).id < spots.get(i).id);
    }

    @Test
    public void build_inChosenOrder() throws IOException {
        List<HomeApi.Course> courses = defaults();
        List<HomeApi.Spot> spots = MyCourse.allSpots(courses);
        int a = spots.get(9).id, b = spots.get(0).id, c = spots.get(5).id;

        HomeApi.Course course = MyCourse.build(courses, Arrays.asList(a, b, 999, c));
        assertEquals(MyCourse.COURSE_ID, course.id);
        assertEquals(MyCourse.NAME, course.name);
        // 없는 장소(999)는 빼고 고른 순서대로
        assertEquals(3, course.spots.size());
        assertEquals(a, course.spots.get(0).spot.id);
        assertEquals(b, course.spots.get(1).spot.id);
        assertEquals(3, course.spots.get(2).position);
        // 걷는 길로 잰 지점별 시간이 없어서 0 (화면은 숨김)
        assertEquals(0, course.spots.get(1).minutes);
    }

    @Test
    public void build_nothingChosen_isNull() throws IOException {
        assertNull(MyCourse.build(defaults(), Arrays.asList()));
        assertNull(MyCourse.build(defaults(), null));
    }

    @Test
    public void encodeDecode() {
        assertEquals("7,1,10", MyCourse.encode(Arrays.asList(7, 1, 10)));
        assertEquals(Arrays.asList(7, 1, 10), MyCourse.decode("7,1,10"));
        assertEquals(Arrays.asList(7, 10), MyCourse.decode("7,x,10,7"));
        assertTrue(MyCourse.decode(null).isEmpty());
    }
}
