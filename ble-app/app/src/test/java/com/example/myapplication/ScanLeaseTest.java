package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ScanLeaseTest {

    /** 가짜 시계: 예약된 일을 시간 순서대로 직접 실행한다 */
    private static class FakeScheduler implements ScanLease.Scheduler {
        long now = 0;
        final List<Object[]> tasks = new ArrayList<>(); // {실행 시각, Runnable}

        @Override
        public Object schedule(Runnable task, long delayMs) {
            Object[] t = {now + delayMs, task};
            tasks.add(t);
            return t;
        }

        @Override
        public void cancel(Object token) {
            tasks.remove(token);
        }

        void advance(long ms) {
            now += ms;
            for (Object[] t : new ArrayList<>(tasks)) {
                if ((long) t[0] <= now && tasks.remove(t)) ((Runnable) t[1]).run();
            }
        }
    }

    private final List<String> log = new ArrayList<>();
    private FakeScheduler clock;
    private ScanLease lease;

    @Before
    public void setUp() {
        log.clear();
        clock = new FakeScheduler();
        lease = new ScanLease(() -> log.add("start"), () -> log.add("stop"), clock);
    }

    @Test
    public void startsOnFirstAcquire() {
        lease.acquire();
        assertTrue(lease.isRunning());
        assertEquals(List.of("start"), log);
    }

    @Test
    public void secondScreenDoesNotRestart() {
        lease.acquire();
        lease.acquire();
        assertEquals(List.of("start"), log);
        assertEquals(2, lease.holders());
    }

    @Test
    public void stopsAfterGraceWhenAllReleased() {
        lease.acquire();
        lease.release();
        clock.advance(ScanLease.GRACE_MS - 1);
        assertTrue(lease.isRunning());
        clock.advance(1);
        assertFalse(lease.isRunning());
        assertEquals(List.of("start", "stop"), log);
    }

    @Test
    public void screenChangeDoesNotRestartScan() {
        // 홈 onStop → 장소 안내 onStart 사이에 잠깐 0이 되어도 스캔을 껐다 켜지 않는다
        lease.acquire();
        lease.release();
        clock.advance(500);
        lease.acquire();
        clock.advance(ScanLease.GRACE_MS * 2);
        assertTrue(lease.isRunning());
        assertEquals(List.of("start"), log);
    }

    @Test
    public void extraReleaseIsIgnored() {
        lease.release();
        assertEquals(0, lease.holders());
        lease.acquire();
        lease.release();
        lease.release();
        assertEquals(0, lease.holders());
        clock.advance(ScanLease.GRACE_MS);
        assertEquals(List.of("start", "stop"), log);
    }

    @Test
    public void restartsAfterStop() {
        lease.acquire();
        lease.release();
        clock.advance(ScanLease.GRACE_MS);
        lease.acquire();
        assertEquals(List.of("start", "stop", "start"), log);
    }
}
