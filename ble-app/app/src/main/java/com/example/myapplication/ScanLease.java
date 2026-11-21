package com.example.myapplication;

/**
 * 스캔을 켜고 끄는 시점을 정한다. 화면들이 "스캔이 필요하다(acquire)", "이제 필요 없다(release)"를 알리면,
 * 하나라도 필요한 동안 스캔을 켜 둔다.
 *
 * 화면을 넘길 때(이전 화면 onStop → 다음 화면 onStart) 잠깐 0이 되므로, 바로 끄지 않고 GRACE_MS 동안 기다린다.
 * 안드로이드는 30초 안에 스캔을 5번 넘게 시작하면 스캔을 막아 버려서, 화면을 오갈 때마다 껐다 켜면 자동 도착이 멈출 수 있다.
 *
 * Android 기능을 쓰지 않는 순수 Java라서 PC에서 단위 테스트로 확인할 수 있다. 메인 스레드에서만 부른다고 가정한다.
 */
public final class ScanLease {

    public static final long GRACE_MS = 3000;

    /** 나중에 실행할 일을 예약하는 쪽. 앱에서는 Handler, 테스트에서는 가짜 시계 */
    public interface Scheduler {
        Object schedule(Runnable task, long delayMs);
        void cancel(Object token);
    }

    private final Runnable onStart;
    private final Runnable onStop;
    private final Scheduler scheduler;

    private int holders = 0;
    private boolean running = false;
    private Object pendingStop = null;

    public ScanLease(Runnable onStart, Runnable onStop, Scheduler scheduler) {
        this.onStart = onStart;
        this.onStop = onStop;
        this.scheduler = scheduler;
    }

    /** 스캔이 필요한 화면이 켜졌다 (onStart) */
    public void acquire() {
        holders++;
        if (pendingStop != null) {
            scheduler.cancel(pendingStop);
            pendingStop = null;
        }
        if (!running) {
            running = true;
            onStart.run();
        }
    }

    /** 스캔이 필요한 화면이 꺼졌다 (onStop). 모두 꺼지면 GRACE_MS 뒤에 스캔을 멈춘다 */
    public void release() {
        if (holders == 0) return;
        holders--;
        if (holders == 0 && running && pendingStop == null) {
            pendingStop = scheduler.schedule(() -> {
                pendingStop = null;
                if (holders == 0 && running) {
                    running = false;
                    onStop.run();
                }
            }, GRACE_MS);
        }
    }

    /** 스캔을 켜 두어야 하는 상태인가 (실제 스캔이 블루투스·권한 문제로 실패했어도 true일 수 있다) */
    public boolean isRunning() {
        return running;
    }

    public int holders() {
        return holders;
    }
}
