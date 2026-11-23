package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ArrivalRouterTest {

    private static HomeApi.Spot spot(int id, int... sensorIds) {
        HomeApi.Spot s = new HomeApi.Spot();
        s.id = id;
        s.arrival = new HomeApi.Arrival();
        s.arrival.method = sensorIds.length > 0 ? "ble_auto" : "manual";
        s.arrival.sensors = new ArrayList<>();
        for (int sid : sensorIds) {
            HomeApi.SpotSensor ss = new HomeApi.SpotSensor();
            ss.sensorId = sid;
            s.arrival.sensors.add(ss);
        }
        return s;
    }

    private static HomeApi.Course course(int id, HomeApi.Spot... spots) {
        HomeApi.Course c = new HomeApi.Course();
        c.id = id;
        c.spots = new ArrayList<>();
        for (HomeApi.Spot s : spots) {
            HomeApi.CourseSpot cs = new HomeApi.CourseSpot();
            cs.spot = s;
            c.spots.add(cs);
        }
        return c;
    }

    // 실제 투어 데이터(#22, PR #38, #47)와 같은 구성: 도서관 뒤편 1, 사회과학대 입구 3, 이마트24 건너편 2, 테니스장 4·5
    private static List<HomeApi.Course> realCourses() {
        HomeApi.Spot library = spot(6, 1), social = spot(4, 3), emart = spot(5, 2), tennis = spot(2, 4, 5);
        HomeApi.Spot gate = spot(1), solmot = spot(7), museum = spot(10);
        return List.of(
                course(1, library, solmot, social),                              // 핵심 코스
                course(2, gate, tennis, social, emart, library, solmot),         // 정문 코스 (일부)
                course(3, museum));                                              // 학생 생활 코스 (일부)
    }

    @Test
    public void tennisCourtFromEitherSensor() {
        Map<Integer, List<Integer>> map = ArrivalRouter.build(realCourses());
        assertEquals(List.of(2), map.get(4));
        assertEquals(List.of(2), map.get(5));
    }

    @Test
    public void spotInSeveralCoursesIsListedOnce() {
        // 도서관 뒤편은 핵심 코스와 정문 코스 둘 다에 있지만 한 번만
        Map<Integer, List<Integer>> map = ArrivalRouter.build(realCourses());
        assertEquals(List.of(6), map.get(1));
        assertEquals(List.of(5), map.get(2));
        assertEquals(List.of(4), map.get(3));
    }

    @Test
    public void manualSpotsHaveNoSensor() {
        Map<Integer, List<Integer>> map = ArrivalRouter.build(realCourses());
        for (List<Integer> ids : map.values()) {
            assertTrue(!ids.contains(1) && !ids.contains(7) && !ids.contains(10));
        }
    }

    @Test
    public void unknownSensorGivesNoSpot() {
        ArrivalRouter.update(realCourses());
        assertTrue(ArrivalRouter.spotIdsForSensor(99).isEmpty());
        assertEquals(List.of(6), ArrivalRouter.spotIdsForSensor(1));
    }

    @Test
    public void emptyUpdateKeepsPreviousMapping() {
        ArrivalRouter.update(realCourses());
        ArrivalRouter.update(null);
        ArrivalRouter.update(new ArrayList<>());
        assertEquals(List.of(2), ArrivalRouter.spotIdsForSensor(4));
    }

    @Test
    public void nullPartsAreSkipped() {
        HomeApi.Course c = new HomeApi.Course();
        c.spots = new ArrayList<>();
        c.spots.add(null);
        HomeApi.CourseSpot cs = new HomeApi.CourseSpot();
        c.spots.add(cs); // spot null
        HomeApi.Spot noArrival = new HomeApi.Spot();
        noArrival.id = 9;
        HomeApi.CourseSpot cs2 = new HomeApi.CourseSpot();
        cs2.spot = noArrival;
        c.spots.add(cs2);
        assertTrue(ArrivalRouter.build(List.of(c)).isEmpty());
    }

    @Test
    public void scanStateLabels() {
        assertEquals("자동 감지 켜짐", ScanState.SCANNING.label);
        assertTrue(ScanState.NO_PERMISSION.needsUserAction());
        assertTrue(ScanState.BLUETOOTH_OFF.needsUserAction());
        assertTrue(!ScanState.SCANNING.needsUserAction());
    }
}
