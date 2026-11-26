package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 방문 기록을 지우면 TourScanner에 알리는지 (센서 앞에 서 있으면 그 장소를 다시 기록하기 위함).
 * "지우고 시작"(forget)과 "기록 지우기"(clear) 둘 다.
 */
public class TourProgressForgetTest {

    private final AtomicInteger notified = new AtomicInteger();

    @Before
    public void setUp() {
        TourProgress.resetForTest();
        TourProgress.setOnForget(notified::incrementAndGet);
    }

    @After
    public void tearDown() {
        TourProgress.resetForTest();
    }

    @Test
    public void forgetNotifiesWhenARecordIsRemoved() {
        TourProgress.markArrived(6, false, 1000L);
        TourProgress.forget(List.of(6));
        assertEquals(1, notified.get());
        assertFalse(TourProgress.isVisited(6));
    }

    @Test
    public void forgetDoesNotNotifyWhenNothingWasRemoved() {
        TourProgress.markArrived(6, false, 1000L);
        TourProgress.forget(List.of(7)); // 기록이 없는 장소
        TourProgress.forget(null);
        assertEquals(0, notified.get());
        assertTrue(TourProgress.isVisited(6));
    }

    @Test
    public void clearNotifies() {
        TourProgress.markArrived(6, false, 1000L);
        TourProgress.clear();
        assertEquals(1, notified.get());
        assertTrue(TourProgress.visits().isEmpty());
    }

    @Test
    public void spotCanBeRecordedAgainAfterForget() {
        // 알림을 받은 TourScanner가 같은 센서로 다시 기록할 때 새 기록으로 받아들여지는지
        TourProgress.markArrived(6, false, 1000L);
        TourProgress.forget(List.of(6));
        assertTrue(TourProgress.markArrived(6, false, 2000L));
        assertEquals(Long.valueOf(2000L), TourProgress.arrivedAt(6));
    }

    @Test
    public void markArrivedDoesNotNotify() {
        TourProgress.markArrived(6, false, 1000L);
        TourProgress.markArrived(6, true, 2000L);
        assertEquals(0, notified.get());
    }
}
