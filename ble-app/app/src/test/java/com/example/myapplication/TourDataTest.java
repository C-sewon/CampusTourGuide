package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TourDataTest {

    /** 앱에 넣어 둔 기본 목록 (서버 docs/tour_default.json 을 복사한 파일) */
    private static List<HomeApi.Course> defaults() throws IOException {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/" + TourData.DEFAULT_ASSET)),
                StandardCharsets.UTF_8);
        List<HomeApi.Course> courses = TourData.parse(json);
        assertNotNull("기본 목록을 읽지 못함", courses);
        return courses;
    }

    /**
     * 앱에 넣은 기본 목록이 서버가 뽑은 파일(저장소 docs/tour_default.json)과 같은지.
     * 장소나 코스가 바뀌어 서버 쪽 파일만 새로 뽑혔으면 실패한다. 그때는 그 파일을 assets 로 다시 복사한다.
     */
    @Test
    public void defaultList_matchesServerExport() throws IOException {
        byte[] app = Files.readAllBytes(Paths.get("src/main/assets/" + TourData.DEFAULT_ASSET));
        byte[] server = Files.readAllBytes(Paths.get("../../docs/tour_default.json"));
        assertEquals("docs/tour_default.json 을 ble-app/app/src/main/assets 로 다시 복사하세요",
                new String(server, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                new String(app, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    public void defaultList_hasRealTour() throws IOException {
        List<HomeApi.Course> courses = defaults();
        assertEquals(3, courses.size());

        Set<Integer> spots = new HashSet<>();
        int cards = 0;
        for (HomeApi.Course c : courses) {
            for (HomeApi.CourseSpot cs : c.spots) {
                if (spots.add(cs.spot.id) && cs.spot.hasCard) cards++;
                // 기본 목록에는 온도가 없다 (예전 온도가 지금 값처럼 보이지 않게)
                assertNull(cs.spot.latestReading);
            }
        }
        assertEquals(12, spots.size());
        assertEquals(6, cards);
    }

    @Test
    public void defaultList_coreCourse() throws IOException {
        HomeApi.Course core = null;
        for (HomeApi.Course c : defaults()) {
            if (c.isCore) {
                assertNull("핵심 코스는 하나", core);
                core = c;
            }
        }
        assertNotNull(core);
        assertEquals("핵심 코스", core.name);
        assertEquals(3, core.spots.size());
        for (HomeApi.CourseSpot cs : core.spots) assertTrue(cs.spot.hasCard);
    }

    @Test
    public void defaultList_arrivalSensors() throws IOException {
        // 자동 도착: 센서 1 → 도서관 뒤편, 센서 4·5 → 테니스장
        ArrivalRouter.update(defaults());
        assertEquals(1, ArrivalRouter.spotIdsForSensor(1).size());
        assertEquals(ArrivalRouter.spotIdsForSensor(4), ArrivalRouter.spotIdsForSensor(5));
    }

    @Test
    public void offlineRecommendation_longestCourse() throws IOException {
        // 기온을 모르면 서버처럼 가장 긴 코스(정문 코스 54분)
        HomeApi.Recommendation rec = TourData.offlineRecommendation(defaults());
        assertEquals("unknown", rec.condition);
        assertNull(rec.temperature);
        assertEquals("정문 코스", rec.recommendedCourseName);
        assertEquals(3, rec.courses.size());
    }

    @Test
    public void savedList_dropsReadings() throws IOException {
        List<HomeApi.Course> courses = defaults();
        HomeApi.Spot first = courses.get(0).spots.get(0).spot;
        first.latestReading = new HomeApi.Reading();
        first.latestReading.temperature = 22;

        List<HomeApi.Course> saved = TourData.parse(TourData.withoutReadings(courses));
        assertNotNull(saved);
        assertNull(saved.get(0).spots.get(0).spot.latestReading);
        // 화면에서 쓰는 원본은 그대로
        assertNotNull(first.latestReading);
        assertEquals(first.id, saved.get(0).spots.get(0).spot.id);
    }

    @Test
    public void parse_badInput() {
        assertNull(TourData.parse(null));
        assertNull(TourData.parse(""));
        assertNull(TourData.parse("[]"));
        assertNull(TourData.parse("not json"));
    }

    @Test
    public void find_byId() throws IOException {
        List<HomeApi.Course> courses = defaults();
        assertEquals(courses.get(1).id, TourData.find(courses, courses.get(1).id).id);
        assertNull(TourData.find(courses, 999));
        assertFalse(TourData.longest(courses) == null);
    }
}
