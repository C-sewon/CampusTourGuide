package com.example.myapplication; // ★주의: 본인 프로젝트 패키지명으로 유지하세요.

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import android.provider.Settings;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE_S = 101;
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final Set<String> TARGET_NAMES = new HashSet<>(Arrays.asList(
            "opensrc1", "opensrc2", "opensrc3", "opensrc4", "opensrc5"));

    private static final long UPLOAD_INTERVAL_MS = 10_000;
    private static final boolean AUTO_UPLOAD = true;

    // ★ 이보다 약한 신호(먼 센서/노이즈)는 무시
    private static final int MIN_VALID_RSSI = -100;

    private BluetoothAdapter blead;
    private BluetoothLeScanner bluetoothLeScanner;
    private TextView tvLog;

    // 버튼을 누를 때까지 데이터를 임시로 모아둘 리스트
    private List<String> pendingCsvData = new ArrayList<>();

    // 센서(기기명)별 마지막 업로드 시각 - 중복 업로드 쓰로틀링용
    private final Map<String, Long> lastUploadMs = new HashMap<>();

    // 수동 전송 버튼용: 마지막으로 수신한 패킷
    private SensorPacket lastPacket;
    private String lastMac;
    private int lastRssi;
    private String lastDeviceName;

    private Retrofit retrofit;

    // 도착 판단: 센서 근처에 왔는지 / 떠났는지를 로그에 표시
    private final ArrivalDetector arrivalDetector = new ArrivalDetector(new ArrivalDetector.Listener() {
        @Override
        public void onArrive(int sensorId) {
            tvLog.append("\n\n[도착] " + Sensors.byId(sensorId) + " - " + Sensors.byId(sensorId).location);
        }

        @Override
        public void onLeave(int sensorId) {
            tvLog.append("\n\n[떠남] " + Sensors.byId(sensorId));
        }
    });

    // 신호가 아예 안 들어와도 "떠남"을 알 수 있도록 2초마다 확인
    private static final long ARRIVAL_TICK_MS = 2000;
    private final android.os.Handler tickHandler = new android.os.Handler(Looper.getMainLooper());
    private final Runnable arrivalTick = new Runnable() {
        @Override
        public void run() {
            arrivalDetector.tick(System.currentTimeMillis());
            tickHandler.postDelayed(this, ARRIVAL_TICK_MS);
        }
    };

    // 재전송 큐: 업로드에 실패한 값을 모아 두었다가 다시 보냄
    private UploadQueue uploadQueue;
    private static final long UPLOAD_FLUSH_MS = 5000;
    private final Runnable uploadFlushTick = new Runnable() {
        @Override
        public void run() {
            if (uploadQueue != null) uploadQueue.flush();
            tickHandler.postDelayed(this, UPLOAD_FLUSH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedBundleInstance) {
        super.onCreate(savedBundleInstance);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);

        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl("http://203.255.81.72:10021/") // 연구실 서버 (센서 공용 API)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // 예전(중간평가 업로드 API) 형식으로 저장된 큐는 새 API로 보낼 수 없으므로 지운다
        File oldQueue = new File(getFilesDir(), "upload_queue.json");
        if (oldQueue.exists() && oldQueue.delete()) {
            tvLog.append("\n[알림] 예전 형식의 재전송 대기 값을 정리했습니다");
        }
        uploadQueue = new UploadQueue(
                this::sendToLabServer,
                new UploadQueue.FileStore(new File(getFilesDir(), "upload_queue_v2.json")),
                System::currentTimeMillis,
                new UploadQueue.Listener() {
                    @Override
                    public void onSent(SensorRequest r, String detail) {
                        tvLog.append("\n[서버 응답: 정상] " + r.getBleName() + "(" + r.getLabName() + ") RSSI " + r.getRssi() + " - " + detail);
                    }

                    @Override
                    public void onDropped(SensorRequest r, String reason) {
                        tvLog.append("\n[전송 포기] " + r.getBleName() + " - " + reason);
                    }

                    @Override
                    public void onRetryScheduled(int queued, long delayMs, String detail) {
                        tvLog.append("\n[재전송 대기] " + queued + "건, " + (delayMs / 1000) + "초 뒤 다시 시도 - " + detail);
                    }
                });
        if (uploadQueue.size() > 0) {
            tvLog.append("\n[재전송 대기] 지난번에 못 보낸 값 " + uploadQueue.size() + "건");
        }
        tickHandler.postDelayed(uploadFlushTick, UPLOAD_FLUSH_MS);

        // 권한 체크 및 초기화
        bleInitialize(this);

        initBluetoothLeScanner();

        Button btnScan = findViewById(R.id.btnScan);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnSave = findViewById(R.id.btnSave); // 저장 버튼 연결
        Button btnSend = findViewById(R.id.btnSend);

        btnScan.setOnClickListener(v -> startScanning());
        btnStop.setOnClickListener(v -> stopScanning());
        btnSave.setOnClickListener(v -> saveBufferedData());
        btnSend.setOnClickListener(v -> {
            if (lastPacket == null) {
                Toast.makeText(this, "전송할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            sendDataToServer(lastPacket, lastMac, lastRssi, lastDeviceName);
        });// 저장 버튼 클릭 시 실행
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 블루투스를 앱 실행 후에 켰거나 권한을 뒤늦게 허용한 경우를 대비해 재확인
        initBluetoothLeScanner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tickHandler.removeCallbacks(arrivalTick);
        tickHandler.removeCallbacks(uploadFlushTick);
    }

    private void initBluetoothLeScanner() {
        blead = BluetoothAdapter.getDefaultAdapter();
        if (blead == null) {
            bluetoothLeScanner = null;
            return;
        }
        if (blead.isEnabled()) {
            bluetoothLeScanner = blead.getBluetoothLeScanner();
        } else {
            bluetoothLeScanner = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            initBluetoothLeScanner();
        } else {
            Toast.makeText(this, "블루투스/위치 권한이 없으면 스캔할 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void bleInitialize(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBlePermissions(activity);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBlePermissions(activity);
            }
        }
    }

    private void requestBlePermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE_S);
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        }
    }

    private void startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toast.makeText(this, "블루투스 권한이 없습니다. 권한을 허용해주세요.", Toast.LENGTH_SHORT).show();
            requestBlePermissions(this);
            return;
        }

        // 스캐너가 아직 준비되지 않았다면(블루투스가 나중에 켜진 경우 등) 재시도
        if (bluetoothLeScanner == null) {
            initBluetoothLeScanner();
        }
        if (blead == null || !blead.isEnabled()) {
            Toast.makeText(this, "블루투스가 꺼져 있습니다. 켜주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "블루투스 스캐너를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        // ★ ScanFilter.setServiceUuid()는 광고 패킷의 "서비스 UUID 목록" AD 필드만 검사한다.
        // 센서가 UUID를 Service Data 필드에만 실어 보내면 이 필터에 안 걸려 onScanResult가 전혀 호출되지 않는다.
        // 필터링은 onScanResult 안에서 기기 이름으로 소프트웨어적으로 하므로 OS 레벨 필터는 걸지 않는다.
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(null, scanSettings, scanCallback);
        tvLog.append("\n\n--- 스캔 시작 ---");

        tickHandler.removeCallbacks(arrivalTick);
        tickHandler.postDelayed(arrivalTick, ARRIVAL_TICK_MS);
    }

    private void stopScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            tvLog.append("\n\n--- 스캔 중지됨 ---");
        }
        tickHandler.removeCallbacks(arrivalTick);
        arrivalDetector.reset();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord scanRecord = result.getScanRecord();
            int rssi = result.getRssi();

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;

            String deviceName = device.getName();
            String deviceAddress = device.getAddress();

            if (deviceName == null || !TARGET_NAMES.contains(deviceName)) return;

            if (rssi == 0 || rssi < MIN_VALID_RSSI) {
                tvLog.append("\n[무효] RSSI " + rssi);
                return;
            }

            // 도착 판단에는 신호 세기만 필요하므로, 값 읽기와 상관없이 먼저 넘긴다
            Sensors.Sensor sensor = Sensors.find(deviceName, deviceAddress);
            if (sensor != null) {
                arrivalDetector.update(sensor.id, rssi, System.currentTimeMillis());
            }

            byte[] scanRecordBytes = scanRecord != null ? scanRecord.getServiceData(ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb")) : null;

            if (scanRecordBytes != null) {
                SensorPacket packet = SensorPacket.parse(scanRecordBytes);
                if (packet != null) {
                    String logStr = "\n[수신] 이름: " + deviceName + ", MAC: " + deviceAddress + ", RSSI: " + rssi + "\n" + packet.toString();
                    tvLog.append(logStr);

                    // ★ 진단용: 원본 바이트를 그대로 로그에 남겨서 AQI/TVOC/eCO2 자리(byte[4], byte[5-6], byte[7-8])가
                    // 수신 시점에 실제로 0인지, 파싱 과정에서 0이 되는지 바로 눈으로 확인할 수 있게 함.
                    tvLog.append("\nRAW(" + scanRecordBytes.length + "B): " + bytesToHex(scanRecordBytes)
                            + "\n  ㄴ aqi byte[4]=" + String.format("%02x", scanRecordBytes[4])
                            + ", tvoc byte[5-6]=" + String.format("%02x%02x", scanRecordBytes[6], scanRecordBytes[5])
                            + ", eco2 byte[7-8]=" + String.format("%02x%02x", scanRecordBytes[8], scanRecordBytes[7]));

                    // ★ 습도, AQI, TVOC까지 모두 포함하여 임시 보관
                    String csvLine = packet.timestamp + "," + deviceName + "," + deviceAddress + "," + rssi + ",0x181A," + packet.eco2 + "," + packet.temperature + "," + packet.humidity + "," + packet.aqi + "," + packet.tvoc + "\n";
                    pendingCsvData.add(csvLine);

                    lastPacket = packet;
                    lastMac = deviceAddress;
                    lastRssi = rssi;
                    lastDeviceName = deviceName;

                    if (AUTO_UPLOAD) {
                        long now = System.currentTimeMillis();
                        Long last = lastUploadMs.get(deviceName);
                        if (last == null || now - last >= UPLOAD_INTERVAL_MS) {
                            lastUploadMs.put(deviceName, now);
                            sendDataToServer(packet, deviceAddress, rssi, deviceName);
                        }
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            tvLog.append("\n스캔 에러 발생 코드: " + errorCode);
        }
    };

    private void saveBufferedData() {
        if (pendingCsvData.isEmpty()) {
            Toast.makeText(this, "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File dir = getExternalFilesDir(null);
            File file = new File(dir, "ble_data.csv");
            boolean fileExists = file.exists();
            FileWriter fw = new FileWriter(file, true);

            // ★ 모든 데이터에 맞게 CSV 컬럼 헤더(맨 윗줄) 변경
            if (!fileExists) {
                fw.append("timestamp,device_name,device_address,rssi,uuid,co2,temperature,humidity,aqi,tvoc\n");
            }

            for (String line : pendingCsvData) {
                fw.append(line);
            }

            fw.flush();
            fw.close();

            int savedCount = pendingCsvData.size();
            pendingCsvData.clear();

            Toast.makeText(this, savedCount + "건의 데이터가 저장되었습니다.", Toast.LENGTH_SHORT).show();
            tvLog.append("\n\n[알림] " + savedCount + "건의 데이터 CSV 저장 완료!");

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "저장 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show();
        }
    }
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void sendDataToServer(SensorPacket packet, String mac, int rssi, String deviceName) {
        // 연구실 서버에는 BLE 이름이 아니라 연구실 이름(sensor 07 등)으로 보낸다
        Sensors.Sensor sensor = Sensors.find(deviceName, mac);
        if (sensor == null) {
            tvLog.append("\n[전송 안 함] 등록되지 않은 센서 " + deviceName);
            return;
        }

        // 2번 API에는 습도 칸이 없어서 서버가 센서 오류값(0.0/0.0 등)을 걸러낼 수 없으므로 앱에서 거른다.
        // "전송" 버튼으로 보내는 수동 전송도 이 함수를 거친다.
        String reject = ReadingCheck.rejectReason(packet.temperature, packet.humidity);
        if (reject != null) {
            tvLog.append("\n[전송 안 함] " + reject + " " + deviceName);
            return;
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

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

        // 바로 보내지 않고 재전송 큐에 넣는다. 네트워크가 정상이면 큐가 바로 보낸다.
        // 같은 센서의 같은 측정 시각은 이미 보냈으므로 다시 넣지 않는다.
        if (uploadQueue.add(request)) {
            tvLog.append("\n\n[전송 시작] " + deviceName + " (RSSI " + rssi + ")");
        } else {
            tvLog.append("\n[중복 생략] " + deviceName + " 측정 시각 " + packet.timestamp + "은 이미 보냄");
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