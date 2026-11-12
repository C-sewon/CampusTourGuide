package com.example.myapplication;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 센서 신호 세기(RSSI)로 "어느 센서 근처에 도착했는지" 판단한다.
 *
 * 규칙 (CampusTourGuide 이슈 #2에서 합의)
 * - 신호 세기가 순간적으로 튀지 않도록, 센서별로 최근 WINDOW_MS 동안 받은 값의 평균을 쓴다.
 * - 여러 센서가 잡히면 평균이 가장 센 센서 하나만 "지금 있는 곳"으로 본다.
 * - 평균이 ARRIVE_RSSI(-70 dBm) 이상이면 도착, LEAVE_RSSI(-80 dBm) 아래로 떨어지면 떠남.
 *   두 기준을 다르게 두어 경계에 서 있을 때 도착과 떠남이 번갈아 뜨지 않게 한다.
 * - 한 번 튄 값으로 도착하지 않도록, 최근 값이 MIN_SAMPLES개 이상 있어야 도착으로 본다.
 *
 * Android 기능을 쓰지 않는 순수 Java라서 PC에서 단위 테스트로 확인할 수 있다.
 */
public class ArrivalDetector {

    public static final int ARRIVE_RSSI = -70;
    public static final int LEAVE_RSSI = -80;
    public static final long WINDOW_MS = 10_000;
    public static final int MIN_SAMPLES = 3;

    public interface Listener {
        void onArrive(int sensorId);
        void onLeave(int sensorId);
    }

    private static final class Sample {
        final long timeMs;
        final int rssi;
        Sample(long timeMs, int rssi) {
            this.timeMs = timeMs;
            this.rssi = rssi;
        }
    }

    private final Map<Integer, Deque<Sample>> samples = new HashMap<>();
    private final Listener listener;
    private Integer current = null;

    public ArrivalDetector(Listener listener) {
        this.listener = listener;
    }

    /** 스캔 결과가 들어올 때마다 부른다. */
    public void update(int sensorId, int rssi, long nowMs) {
        Deque<Sample> q = samples.get(sensorId);
        if (q == null) {
            q = new ArrayDeque<>();
            samples.put(sensorId, q);
        }
        q.addLast(new Sample(nowMs, rssi));
        evaluate(nowMs);
    }

    /**
     * 신호가 아예 안 들어올 때도 "떠남"을 알 수 있도록 주기적으로(예: 2초마다) 부른다.
     */
    public void tick(long nowMs) {
        evaluate(nowMs);
    }

    /** 스캔을 멈출 때 부른다. 도착 상태였다면 떠남으로 처리하고 기록을 비운다. */
    public void reset() {
        samples.clear();
        if (current != null) {
            int left = current;
            current = null;
            listener.onLeave(left);
        }
    }

    /** 지금 도착해 있는 센서 번호. 없으면 null. */
    public Integer getCurrent() {
        return current;
    }

    /** 센서의 최근 평균 신호 세기. 최근 값이 없으면 null. */
    public Double averageRssi(int sensorId, long nowMs) {
        Deque<Sample> q = samples.get(sensorId);
        if (q == null) return null;
        prune(q, nowMs);
        if (q.isEmpty()) return null;
        double sum = 0;
        for (Sample s : q) sum += s.rssi;
        return sum / q.size();
    }

    private void prune(Deque<Sample> q, long nowMs) {
        while (!q.isEmpty() && nowMs - q.peekFirst().timeMs > WINDOW_MS) {
            q.removeFirst();
        }
    }

    private void evaluate(long nowMs) {
        // 도착 후보: 최근 값이 충분히 있고 평균이 가장 센 센서
        Integer best = null;
        double bestAvg = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Deque<Sample>> e : samples.entrySet()) {
            Double avg = averageRssi(e.getKey(), nowMs);
            if (avg == null || e.getValue().size() < MIN_SAMPLES) continue;
            if (avg > bestAvg) {
                bestAvg = avg;
                best = e.getKey();
            }
        }

        if (current != null) {
            Double curAvg = averageRssi(current, nowMs);
            if (curAvg == null || curAvg < LEAVE_RSSI) {
                // 신호가 약해졌거나 한동안 안 들어옴 → 떠남
                int left = current;
                current = null;
                listener.onLeave(left);
            } else if (best != null && !best.equals(current)
                    && bestAvg >= ARRIVE_RSSI && bestAvg > curAvg) {
                // 다른 센서가 도착 기준을 넘고 더 세면 그쪽으로 옮김
                int left = current;
                current = best;
                listener.onLeave(left);
                listener.onArrive(best);
                return;
            }
        }

        if (current == null && best != null && bestAvg >= ARRIVE_RSSI) {
            current = best;
            listener.onArrive(best);
        }
    }
}
