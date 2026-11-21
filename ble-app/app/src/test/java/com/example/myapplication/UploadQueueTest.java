package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class UploadQueueTest {

    /** 미리 정해 둔 결과를 차례로 돌려주는 가짜 서버. 결과를 바로(동기로) 알려준다. */
    private static class FakeSender implements UploadQueue.Sender {
        final Deque<UploadQueue.Result> results = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();
        UploadQueue.Done pending; // holdNext가 true면 결과를 보류

        boolean holdNext = false;

        @Override
        public void send(SensorRequest request, UploadQueue.Done done) {
            sent.add(request.getBleName() + "@" + request.getTimestamp());
            if (holdNext) {
                holdNext = false;
                pending = done;
                return;
            }
            UploadQueue.Result r = results.isEmpty() ? UploadQueue.Result.SUCCESS : results.removeFirst();
            done.onResult(r, r.name());
        }
    }

    private static class MemoryStore implements UploadQueue.Store {
        List<SensorRequest> saved = new ArrayList<>();
        @Override public List<SensorRequest> load() { return new ArrayList<>(saved); }
        @Override public void save(List<SensorRequest> items) { saved = new ArrayList<>(items); }
    }

    private final List<String> log = new ArrayList<>();
    private final UploadQueue.Listener listener = new UploadQueue.Listener() {
        @Override public void onSent(SensorRequest r, String d) { log.add("sent " + r.getBleName()); }
        @Override public void onDropped(SensorRequest r, String d) { log.add("dropped " + r.getBleName()); }
        @Override public void onRetryScheduled(int q, long delay, String d) { log.add("retry " + q + " " + delay); }
    };

    private FakeSender sender;
    private MemoryStore store;
    private long now;
    private UploadQueue queue;

    @Before
    public void setUp() {
        sender = new FakeSender();
        store = new MemoryStore();
        now = 0;
        queue = new UploadQueue(sender, store, () -> now, listener);
    }

    private static SensorRequest req(String sensor, long ts) {
        return new SensorRequest(sensor, "sensor 07", "88:A2:9E:9B:5F:09", "phone", 20.0, 600, ts, -60);
    }

    @Test
    public void sendsImmediatelyWhenOnline() {
        queue.add(req("opensrc1", 1));
        assertEquals(0, queue.size());
        assertEquals(List.of("sent opensrc1"), log);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    public void keepsFailedValueAndRetriesLater() {
        sender.results.add(UploadQueue.Result.RETRY);
        queue.add(req("opensrc1", 1));
        assertEquals(1, queue.size());
        assertEquals(1, store.saved.size());

        // 재시도 간격 전에는 다시 보내지 않음
        now = 4_999;
        queue.flush();
        assertEquals(1, sender.sent.size());

        now = 5_000;
        queue.flush();
        assertEquals(0, queue.size());
        assertEquals(List.of("retry 1 5000", "sent opensrc1"), log);
    }

    @Test
    public void newValueWaitsBehindFailedOneInOrder() {
        sender.results.add(UploadQueue.Result.RETRY);
        queue.add(req("opensrc1", 1));
        queue.add(req("opensrc2", 2)); // 재시도 대기 중이라 바로 보내지 않음
        assertEquals(2, queue.size());
        assertEquals(1, sender.sent.size());

        now = 5_000;
        queue.flush();
        assertEquals(0, queue.size());
        assertEquals(List.of("opensrc1@1", "opensrc1@1", "opensrc2@2"), sender.sent);
    }

    @Test
    public void dropsRequestsTheServerRejects() {
        sender.results.add(UploadQueue.Result.DROP);
        queue.add(req("opensrc1", 1));
        assertEquals(0, queue.size());
        assertEquals(List.of("dropped opensrc1"), log);
    }

    @Test
    public void backoffDoublesUpToFiveMinutesAndResetsOnSuccess() {
        assertEquals(5_000, UploadQueue.backoffMs(1));
        assertEquals(10_000, UploadQueue.backoffMs(2));
        assertEquals(20_000, UploadQueue.backoffMs(3));
        assertEquals(300_000, UploadQueue.backoffMs(20));

        sender.results.add(UploadQueue.Result.RETRY);
        sender.results.add(UploadQueue.Result.RETRY);
        queue.add(req("opensrc1", 1));          // 실패 1 → 5초
        now = 5_000; queue.flush();             // 실패 2 → 10초
        assertEquals(10_000, queue.millisUntilNextAttempt());
        now = 15_000; queue.flush();            // 성공
        assertEquals(0, queue.millisUntilNextAttempt());

        sender.results.add(UploadQueue.Result.RETRY);
        queue.add(req("opensrc2", 2));          // 다시 5초부터
        assertEquals(5_000, queue.millisUntilNextAttempt());
    }

    @Test
    public void oldestDroppedWhenFull() {
        sender.results.add(UploadQueue.Result.RETRY);
        for (int i = 0; i < UploadQueue.MAX_SIZE + 3; i++) {
            queue.add(req("s" + i, i));
        }
        assertEquals(UploadQueue.MAX_SIZE, queue.size());
        assertEquals(3, log.stream().filter(s -> s.startsWith("dropped")).count());
    }

    @Test
    public void onlyOneRequestInFlight() {
        sender.holdNext = true;
        queue.add(req("opensrc1", 1));
        queue.add(req("opensrc2", 2));
        queue.flush();
        assertEquals(1, sender.sent.size());

        sender.pending.onResult(UploadQueue.Result.SUCCESS, "ok");
        // opensrc1 성공 후 opensrc2가 이어서 전송됨
        assertEquals(0, queue.size());
        assertEquals(List.of("opensrc1@1", "opensrc2@2"), sender.sent);
    }

    @Test
    public void classifiesHttpCodes() {
        assertEquals(UploadQueue.Result.SUCCESS, UploadQueue.classify(200));
        assertEquals(UploadQueue.Result.SUCCESS, UploadQueue.classify(201));
        assertEquals(UploadQueue.Result.DROP, UploadQueue.classify(400));
        assertEquals(UploadQueue.Result.DROP, UploadQueue.classify(404));
        assertEquals(UploadQueue.Result.DROP, UploadQueue.classify(422));
        assertEquals(UploadQueue.Result.RETRY, UploadQueue.classify(408));
        assertEquals(UploadQueue.Result.RETRY, UploadQueue.classify(429));
        assertEquals(UploadQueue.Result.RETRY, UploadQueue.classify(500));
        assertEquals(UploadQueue.Result.RETRY, UploadQueue.classify(503));
    }

    @Test
    public void survivesRestartThroughFileStore() throws Exception {
        File dir = Files.createTempDirectory("uq").toFile();
        File file = new File(dir, "upload_queue.json");

        sender.results.add(UploadQueue.Result.RETRY);
        UploadQueue first = new UploadQueue(sender, new UploadQueue.FileStore(file), () -> now, listener);
        first.add(req("opensrc1", 111));
        first.add(req("opensrc2", 222));
        assertTrue(file.exists());

        // 앱을 다시 켠 상황: 파일에서 큐를 되살림
        FakeSender sender2 = new FakeSender();
        UploadQueue second = new UploadQueue(sender2, new UploadQueue.FileStore(file), () -> now, listener);
        assertEquals(2, second.size());
        second.flush();
        assertEquals(List.of("opensrc1@111", "opensrc2@222"), sender2.sent);
        assertEquals(0, new UploadQueue.FileStore(file).load().size());
    }

    @Test
    public void sameSensorAndTimeIsAddedOnce() {
        assertTrue(queue.add(req("opensrc1", 100)));
        assertEquals(false, queue.add(req("opensrc1", 100)));   // 같은 값 → 생략
        assertTrue(queue.add(req("opensrc1", 110)));            // 새 측정 시각 → 보냄
        assertTrue(queue.add(req("opensrc2", 100)));            // 다른 센서 → 보냄
        assertEquals(List.of("opensrc1@100", "opensrc1@110", "opensrc2@100"), sender.sent);
    }

    @Test
    public void duplicateCheckSurvivesRestartForQueuedValues() throws Exception {
        File dir = Files.createTempDirectory("upload_queue").toFile();
        File file = new File(dir, "upload_queue.json");
        sender.results.add(UploadQueue.Result.RETRY);
        new UploadQueue(sender, new UploadQueue.FileStore(file), () -> now, listener)
                .add(req("opensrc1", 100));

        UploadQueue restarted = new UploadQueue(new FakeSender(), new UploadQueue.FileStore(file), () -> now, listener);
        assertEquals(false, restarted.add(req("opensrc1", 100)));
        assertEquals(1, restarted.size());
    }

    @Test
    public void brokenFileStartsEmpty() throws Exception {
        File file = File.createTempFile("upload_queue", ".json");
        Files.writeString(file.toPath(), "{not json");
        assertEquals(0, new UploadQueue.FileStore(file).load().size());
    }
}
