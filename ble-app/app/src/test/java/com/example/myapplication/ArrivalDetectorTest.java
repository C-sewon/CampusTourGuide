package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ArrivalDetectorTest {

    private final List<String> events = new ArrayList<>();
    private ArrivalDetector detector;

    @Before
    public void setUp() {
        events.clear();
        detector = new ArrivalDetector(new ArrivalDetector.Listener() {
            @Override public void onArrive(int sensorId) { events.add("arrive " + sensorId); }
            @Override public void onLeave(int sensorId) { events.add("leave " + sensorId); }
        });
    }

    private void feed(int sensorId, int rssi, long startMs, int count) {
        for (int i = 0; i < count; i++) {
            detector.update(sensorId, rssi, startMs + i * 1000L);
        }
    }

    @Test
    public void strongSignalArrives() {
        feed(1, -60, 0, 3);
        assertEquals(Integer.valueOf(1), detector.getCurrent());
        assertEquals(List.of("arrive 1"), events);
    }

    @Test
    public void weakSignalDoesNotArrive() {
        feed(1, -75, 0, 5);
        assertNull(detector.getCurrent());
        assertEquals(List.of(), events);
    }

    @Test
    public void oneSpikeDoesNotArrive() {
        detector.update(1, -50, 0);
        detector.update(1, -50, 1000);
        assertNull(detector.getCurrent());
    }

    @Test
    public void staysBetweenThresholds() {
        feed(1, -60, 0, 3);
        // -75는 도착 기준(-70)보다 약하지만 떠남 기준(-80)보다 세서 그대로 도착 상태
        feed(1, -75, 3000, 20);
        assertEquals(Integer.valueOf(1), detector.getCurrent());
        assertEquals(List.of("arrive 1"), events);
    }

    @Test
    public void weakSignalLeaves() {
        feed(1, -60, 0, 3);
        feed(1, -90, 3000, 20);
        assertNull(detector.getCurrent());
        assertEquals(List.of("arrive 1", "leave 1"), events);
    }

    @Test
    public void noSignalLeavesOnTick() {
        feed(1, -60, 0, 3);
        detector.tick(2000 + ArrivalDetector.WINDOW_MS + 1);
        assertNull(detector.getCurrent());
        assertEquals(List.of("arrive 1", "leave 1"), events);
    }

    @Test
    public void picksStrongestSensor() {
        for (int i = 0; i < 3; i++) {
            detector.update(1, -68, i * 1000L);
            detector.update(2, -55, i * 1000L);
        }
        assertEquals(Integer.valueOf(2), detector.getCurrent());
    }

    @Test
    public void movesToCloserSensor() {
        feed(1, -65, 0, 3);
        for (int i = 0; i < 12; i++) {
            long t = 3000 + i * 1000L;
            detector.update(1, -78, t);
            detector.update(2, -55, t);
        }
        assertEquals(Integer.valueOf(2), detector.getCurrent());
        assertEquals(List.of("arrive 1", "leave 1", "arrive 2"), events);
    }

    @Test
    public void resetLeaves() {
        feed(1, -60, 0, 3);
        detector.reset();
        assertNull(detector.getCurrent());
        assertEquals(List.of("arrive 1", "leave 1"), events);
    }

    @Test
    public void findsSensorByMacThenName() {
        assertEquals(3, Sensors.find(null, "b8:27:eb:d3:40:06").id);
        assertEquals(5, Sensors.find("opensrc5", "00:00:00:00:00:00").id);
        assertNull(Sensors.find("other", "00:00:00:00:00:00"));
    }
}
