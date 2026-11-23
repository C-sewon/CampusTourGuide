package com.example.myapplication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * 센서 측정값을 연구실 서버(2번 API)로 올린다. (#26, PR #32)
 * 예전에는 MainActivity 안에 있었고, 투어 화면에서도 업로드되도록 TourScanner가 쓰게 옮겼다. (#29)
 *
 * - 같은 센서는 UPLOAD_INTERVAL_MS(10초)에 한 번만 자동 업로드한다.
 * - 센서 오류값(온도·습도 모두 0.0, 온도 -20~45℃ 밖)은 올리지 않는다. (ReadingCheck)
 * - 재전송 큐(UploadQueue)를 거친다. 실패하면 5초~5분 간격으로 다시 보낸다.
 */
public final class LabUploader {

    public static final long UPLOAD_INTERVAL_MS = 10_000;
    private static final long FLUSH_MS = 5000;

    /** 로그 한 줄을 남기는 쪽 (개발용 센서 로그 화면) */
    public interface Log {
        void line(String text);
    }

    private final Context app;
    private final Log log;
    private final Retrofit retrofit;
    private final UploadQueue queue;
    private final Map<String, Long> lastUploadMs = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flushTick = new Runnable() {
        @Override
        public void run() {
            queue.flush();
            handler.postDelayed(this, FLUSH_MS);
        }
    };

    public LabUploader(Context context, Log log) {
        this.app = context.getApplicationContext();
        this.log = log;

        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl("http://203.255.81.72:10021/") // 연구실 서버 (센서 공용 API)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // 예전(중간평가 업로드 API) 형식으로 저장된 큐는 새 API로 보낼 수 없으므로 지운다
        File oldQueue = new File(app.getFilesDir(), "upload_queue.json");
        if (oldQueue.exists() && oldQueue.delete()) {
            log.line("[알림] 예전 형식의 재전송 대기 값을 정리했습니다");
        }
        queue = new UploadQueue(
                this::sendToLabServer,
                new UploadQueue.FileStore(new File(app.getFilesDir(), "upload_queue_v2.json")),
                System::currentTimeMillis,
                new UploadQueue.Listener() {
                    @Override
                    public void onSent(SensorRequest r, String detail) {
                        log.line("[서버 응답: 정상] " + r.getBleName() + "(" + r.getLabName() + ") RSSI " + r.getRssi() + " - " + detail);
                    }

                    @Override
                    public void onDropped(SensorRequest r, String reason) {
                        log.line("[전송 포기] " + r.getBleName() + " - " + reason);
                    }

                    @Override
                    public void onRetryScheduled(int queued, long delayMs, String detail) {
                        log.line("[재전송 대기] " + queued + "건, " + (delayMs / 1000) + "초 뒤 다시 시도 - " + detail);
                    }
                });
        if (queue.size() > 0) {
            log.line("[재전송 대기] 지난번에 못 보낸 값 " + queue.size() + "건");
        }
        // 재전송은 스캔을 멈춰도 계속한다 (앱이 켜져 있는 동안)
        handler.postDelayed(flushTick, FLUSH_MS);
    }

    /** 스캔으로 받은 측정값. 같은 센서는 10초에 한 번만 올린다 */
    public void onPacket(SensorPacket packet, String mac, int rssi, String deviceName) {
        long now = System.currentTimeMillis();
        Long last = lastUploadMs.get(deviceName);
        if (last != null && now - last < UPLOAD_INTERVAL_MS) return;
        lastUploadMs.put(deviceName, now);
        send(packet, mac, rssi, deviceName);
    }

    /** 개발용 화면의 "전송" 버튼. 10초 제한 없이 바로 큐에 넣는다 */
    public void sendNow(SensorPacket packet, String mac, int rssi, String deviceName) {
        send(packet, mac, rssi, deviceName);
    }

    private void send(SensorPacket packet, String mac, int rssi, String deviceName) {
        // 연구실 서버에는 BLE 이름이 아니라 연구실 이름(sensor 07 등)으로 보낸다
        Sensors.Sensor sensor = Sensors.find(deviceName, mac);
        if (sensor == null) {
            log.line("[전송 안 함] 등록되지 않은 센서 " + deviceName);
            return;
        }

        // 2번 API에는 습도 칸이 없어서 서버가 센서 오류값(0.0/0.0 등)을 걸러낼 수 없으므로 앱에서 거른다
        String reject = ReadingCheck.rejectReason(packet.temperature, packet.humidity);
        if (reject != null) {
            log.line("[전송 안 함] " + reject + " " + deviceName);
            return;
        }

        String deviceId = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);

        SensorRequest request = new SensorRequest(
                deviceName,                                   // 로그·중복 확인용 BLE 이름
                sensor.labName,                               // sensor: 연구실 이름
                mac,                                          // mac: 폰에서 보이는 센서 주소
                deviceId,                                     // sender: 스마트폰 ID
                Math.round(packet.temperature * 100) / 100.0, // temp: ℃ (float 오차 제거)
                packet.eco2,                                  // co2: 오픈소스 센서는 eCO2
                packet.timestamp,                             // time: 측정 시각 (Unix 초)
                rssi
        );

        // 같은 센서의 같은 측정 시각은 이미 보냈으므로 다시 넣지 않는다
        if (queue.add(request)) {
            log.line("[전송 시작] " + deviceName + " (RSSI " + rssi + ")");
        } else {
            log.line("[중복 생략] " + deviceName + " 측정 시각 " + packet.timestamp + "은 이미 보냄");
        }
    }

    /** 재전송 큐가 실제로 연구실 서버(2번 API)에 보낼 때 쓰는 함수 */
    private void sendToLabServer(SensorRequest request, UploadQueue.Done done) {
        ApiService apiService = retrofit.create(ApiService.class);
        ApiService.send(apiService, request).enqueue(new Callback<ApiService.SensingResponse>() {
            @Override
            public void onResponse(Call<ApiService.SensingResponse> call, Response<ApiService.SensingResponse> response) {
                // 응답 처리 중 예상 밖의 예외가 나도 done을 반드시 한 번 불러야 큐가 멈추지 않는다. (PR #25 리뷰)
                // done 호출은 try 밖에 두어, 큐 쪽에서 난 예외 때문에 done이 두 번 불리지 않게 한다.
                UploadQueue.Result result;
                String detail;
                try {
                    result = UploadQueue.classify(response.code());
                    if (response.isSuccessful() && response.body() != null) {
                        detail = response.body().toString();
                    } else {
                        String errorDetail;
                        try {
                            errorDetail = response.errorBody() != null ? response.errorBody().string() : "(본문 없음)";
                        } catch (IOException e) {
                            errorDetail = "(에러 본문 읽기 실패: " + e.getMessage() + ")";
                        }
                        detail = "HTTP " + response.code() + " - " + errorDetail;
                    }
                } catch (RuntimeException e) {
                    result = UploadQueue.Result.RETRY;
                    detail = "응답 처리 오류: " + e;
                }
                done.onResult(result, detail);
            }

            @Override
            public void onFailure(Call<ApiService.SensingResponse> call, Throwable t) {
                // 응답을 못 받음 (네트워크 끊김, 시간 초과 등) → 나중에 다시 보냄
                done.onResult(UploadQueue.Result.RETRY, "전송 실패: " + t.getMessage());
            }
        });
    }
}
