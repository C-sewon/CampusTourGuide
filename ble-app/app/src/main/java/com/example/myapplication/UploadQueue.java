package com.example.myapplication;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 서버 업로드 재전송 큐.
 *
 * 업로드할 측정값은 모두 이 큐를 거친다. 네트워크가 정상이면 바로 보내고,
 * 실패하면 큐에 남겨 두었다가 간격을 늘려 가며 다시 보낸다.
 *
 * 규칙
 * - 성공(2xx): 큐에서 뺀다.
 * - 다시 보내도 실패할 요청(408, 429를 뺀 4xx): 큐에서 버린다.
 * - 네트워크 오류, 서버 오류(5xx), 408, 429: 큐에 남기고 나중에 다시 보낸다.
 * - 같은 센서의 같은 측정 시각은 한 번만 넣는다. (최근 SEEN_KEYS_MAX건 기억)
 * - 한 번에 한 건씩, 들어온 순서대로 보낸다.
 * - 재시도 간격은 5초에서 시작해 실패할 때마다 두 배, 최대 5분. 성공하면 다시 5초.
 * - 큐는 최대 MAX_SIZE건. 넘으면 가장 오래된 것부터 버린다.
 * - 큐 내용은 파일에 저장해서 앱을 껐다 켜도 남는다.
 *
 * 모든 메서드는 메인 스레드에서 부른다고 가정한다. (스캔 콜백, Handler, Retrofit 콜백이 모두 메인 스레드)
 * Android 기능을 쓰지 않는 순수 Java라서 PC에서 단위 테스트로 확인할 수 있다.
 */
public class UploadQueue {

    public static final int MAX_SIZE = 500;
    public static final long BASE_BACKOFF_MS = 5_000;
    public static final long MAX_BACKOFF_MS = 5 * 60_000;

    /** 한 번 보낸 결과 */
    public enum Result { SUCCESS, DROP, RETRY }

    /** 실제로 서버에 보내는 쪽. 다 보내면 반드시 done을 한 번 부른다. */
    public interface Sender {
        void send(SensorRequest request, Done done);
    }

    public interface Done {
        void onResult(Result result, String detail);
    }

    /** 화면 로그용 알림 */
    public interface Listener {
        void onSent(SensorRequest request, String detail);
        void onDropped(SensorRequest request, String reason);
        void onRetryScheduled(int queued, long delayMs, String detail);
    }

    /** 큐 저장소. 파일 저장은 FileStore, 테스트는 메모리 저장소를 쓴다. */
    public interface Store {
        List<SensorRequest> load();
        void save(List<SensorRequest> items);
    }

    /** HTTP 응답 코드를 결과로 바꾼다. 응답 자체를 못 받았으면(네트워크 오류) RETRY. */
    public static Result classify(int httpCode) {
        if (httpCode >= 200 && httpCode < 300) return Result.SUCCESS;
        if (httpCode == 408 || httpCode == 429) return Result.RETRY;
        if (httpCode >= 400 && httpCode < 500) return Result.DROP;
        return Result.RETRY;
    }

    /** 중복 확인을 위해 기억해 두는 (센서, 측정 시각) 개수 */
    public static final int SEEN_KEYS_MAX = 1000;

    private final Deque<SensorRequest> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
    private final Sender sender;
    private final Store store;
    private final LongSupplier clock;
    private final Listener listener;

    private boolean inFlight = false;
    private int failures = 0;
    private long nextAttemptAt = 0;

    public UploadQueue(Sender sender, Store store, LongSupplier clock, Listener listener) {
        this.sender = sender;
        this.store = store;
        this.clock = clock;
        this.listener = listener;
        List<SensorRequest> saved = store.load();
        if (saved != null) {
            for (SensorRequest r : saved) {
                if (r != null) {
                    queue.addLast(r);
                    rememberKey(keyOf(r));
                }
            }
        }
    }

    /**
     * 보낼 측정값을 넣고 바로 보내기를 시도한다.
     * 같은 센서의 같은 측정 시각을 이미 넣은 적이 있으면 넣지 않고 false를 돌려준다.
     * (센서는 약 10초마다 값을 바꾸므로, 그 사이에 여러 번 스캔된 같은 값을 다시 보내지 않기 위함)
     */
    public boolean add(SensorRequest request) {
        String key = keyOf(request);
        if (seenKeys.contains(key)) return false;
        rememberKey(key);

        queue.addLast(request);
        while (queue.size() > MAX_SIZE) {
            SensorRequest old = queue.removeFirst();
            listener.onDropped(old, "큐가 가득 차서 가장 오래된 값 버림");
        }
        persist();
        flush();
        return true;
    }

    private static String keyOf(SensorRequest r) {
        return r.getBleName() + "@" + r.getTimestamp();
    }

    private void rememberKey(String key) {
        seenKeys.add(key);
        if (seenKeys.size() > SEEN_KEYS_MAX) {
            Iterator<String> it = seenKeys.iterator();
            it.next();
            it.remove(); // 가장 오래 전에 기억한 것부터 잊음
        }
    }

    /** 보낼 때가 됐으면 큐 맨 앞부터 보낸다. 주기적으로(예: 5초마다) 부른다. */
    public void flush() {
        if (inFlight || queue.isEmpty()) return;
        if (clock.getAsLong() < nextAttemptAt) return;

        final SensorRequest head = queue.peekFirst();
        inFlight = true;
        sender.send(head, (result, detail) -> {
            inFlight = false;
            switch (result) {
                case SUCCESS:
                    removeHead(head);
                    failures = 0;
                    nextAttemptAt = 0;
                    listener.onSent(head, detail);
                    flush();
                    break;
                case DROP:
                    removeHead(head);
                    listener.onDropped(head, detail);
                    flush();
                    break;
                case RETRY:
                default:
                    failures++;
                    long delay = backoffMs(failures);
                    nextAttemptAt = clock.getAsLong() + delay;
                    listener.onRetryScheduled(queue.size(), delay, detail);
                    break;
            }
        });
    }

    public int size() {
        return queue.size();
    }

    /** 다음 재시도까지 남은 시간. 바로 보낼 수 있으면 0. */
    public long millisUntilNextAttempt() {
        return Math.max(0, nextAttemptAt - clock.getAsLong());
    }

    static long backoffMs(int failures) {
        long delay = BASE_BACKOFF_MS;
        for (int i = 1; i < failures && delay < MAX_BACKOFF_MS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private void removeHead(SensorRequest head) {
        // 보내는 동안 큐가 가득 차서 head가 이미 밀려났을 수 있으므로 같은 객체일 때만 뺀다
        if (queue.peekFirst() == head) {
            queue.removeFirst();
        }
        persist();
    }

    private void persist() {
        store.save(new ArrayList<>(queue));
    }

    /** 큐를 JSON 파일로 저장한다. 쓰다가 앱이 꺼져도 깨지지 않도록 임시 파일에 쓴 뒤 바꿔 끼운다. */
    public static class FileStore implements Store {
        private static final Type LIST_TYPE = new TypeToken<List<SensorRequest>>() {}.getType();
        private final File file;
        private final Gson gson = new Gson();

        public FileStore(File file) {
            this.file = file;
        }

        @Override
        public List<SensorRequest> load() {
            if (!file.exists()) return new ArrayList<>();
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                List<SensorRequest> items = gson.fromJson(reader, LIST_TYPE);
                return items != null ? items : new ArrayList<>();
            } catch (IOException | RuntimeException e) {
                // 파일이 깨졌으면 비운 채로 시작한다
                return new ArrayList<>();
            }
        }

        @Override
        public void save(List<SensorRequest> items) {
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(items, LIST_TYPE, writer);
            } catch (IOException e) {
                return;
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 저장에 실패해도 메모리의 큐는 그대로 동작한다
            }
        }
    }
}
