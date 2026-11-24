package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TourProgressTest {

    @Before
    public void setUp() {
        TourProgress.resetForTest();
    }

    @After
    public void tearDown() {
        TourProgress.resetForTest();
    }

    @Test
    public void firstArrivalOnly_keepsFirstTime() {
        assertTrue(TourProgress.markArrived(6, false, 1_000L));
        // 같은 장소에 다시 도착해도 처음 시각과 방식만 남는다 (UC-03 E3)
        assertFalse(TourProgress.markArrived(6, true, 9_000L));
        assertEquals(Long.valueOf(1_000L), TourProgress.arrivedAt(6));
        assertFalse(TourProgress.isManual(6));
        assertNull(TourProgress.arrivedAt(7));
    }

    @Test
    public void visits_inArrivalOrder() {
        TourProgress.markArrived(7, true, 2_000L);
        TourProgress.markArrived(6, false, 1_000L);
        List<TourProgress.Visit> visits = TourProgress.visits();
        assertEquals(2, visits.size());
        assertEquals(7, visits.get(0).spotId);
        assertTrue(visits.get(0).manual);
        assertEquals(6, visits.get(1).spotId);
    }

    @Test
    public void encodeDecode_roundTrip() {
        List<TourProgress.Visit> in = Arrays.asList(
                new TourProgress.Visit(6, false, 1_700_000_000_000L),
                new TourProgress.Visit(7, true, 1_700_000_300_000L));
        String text = TourProgress.encode(in);
        assertEquals("6,0,1700000000000;7,1,1700000300000", text);
        List<TourProgress.Visit> out = TourProgress.decode(text);
        assertEquals(2, out.size());
        assertEquals(7, out.get(1).spotId);
        assertTrue(out.get(1).manual);
        assertEquals(1_700_000_300_000L, out.get(1).arrivedAt);
    }

    @Test
    public void decode_skipsBadItems() {
        assertTrue(TourProgress.decode(null).isEmpty());
        assertTrue(TourProgress.decode("").isEmpty());
        List<TourProgress.Visit> out = TourProgress.decode("6,0,100;oops;7,1;8,1,abc;9,1,200");
        assertEquals(2, out.size());
        assertEquals(9, out.get(1).spotId);
    }

    @Test
    public void clear_removesAll() {
        TourProgress.markArrived(6, false, 1L);
        TourProgress.clear();
        assertFalse(TourProgress.isVisited(6));
        assertTrue(TourProgress.visits().isEmpty());
    }
}
