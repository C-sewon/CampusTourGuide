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
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
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

    private static final int TEAM_NO = 1;
    private static final String TEAM_KEY = "opensrc-team1";
    private static final Set<String> TARGET_NAMES = new HashSet<>(Arrays.asList(
            "opensrc1", "opensrc2", "opensrc3", "opensrc4", "opensrc5"));

    private static final long UPLOAD_INTERVAL_MS = 10_000;
    private static final boolean AUTO_UPLOAD = true;

    // ★ 이보다 약한 신호(먼 센서/노이즈)는 무시
    private static final int MIN_VALID_RSSI = -100;

    private BluetoothAdapter blead;
    private BluetoothLeScanner bluetoothLeScanner;
    private TextView tvLog;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private volatile double curLat = 0.0;
    private volatile double curLon = 0.0;
    private volatile boolean hasLocation = false;

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

    @Override
    protected void onCreate(Bundle savedBundleInstance) {
        super.onCreate(savedBundleInstance);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);

        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl("http://203.255.81.72:10021/") // ★수정: API 슬라이드 기준 주소/포트로 변경 (기존 10.255.81.72:10024)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // 권한 체크 및 초기화
        bleInitialize(this);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        startLocationUpdates();

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
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocationUpdates();
        tickHandler.removeCallbacks(arrivalTick);
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        }
        if (locationCallback != null) {
            // 이미 등록되어 있으면 중복 등록하지 않음
            return;
        }
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                android.location.Location location = result.getLastLocation();
                if (location == null) return;
                curLat = location.getLatitude();
                curLon = location.getLongitude();
                hasLocation = true;
            }
        };
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        locationCallback = null;
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
            startLocationUpdates();
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
        if (!hasLocation || (curLat == 0.0 && curLon == 0.0)) {
            tvLog.append("\n[위치 미수신] 야외에서 잠시 대기");
            return;
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        ApiService apiService = retrofit.create(ApiService.class);

        SensorRequest request = new SensorRequest(
                TEAM_KEY,           // key
                deviceName,         // sensor - 센서 이름 (opensrc1~opensrc5)
                mac,                // mac - 실제 센서 맥주소
                packet.temperature,
                packet.humidity,
                packet.aqi,
                packet.tvoc,
                packet.eco2,
                packet.timestamp,
                curLat,
                curLon,
                deviceId,           // sender
                rssi
        );

        tvLog.append("\n\n[전송 시작] " + deviceName + " (RSSI " + rssi + ")");

        apiService.sendSensorData(request).enqueue(new Callback<SensorResponse>() {
            @Override
            public void onResponse(Call<SensorResponse> call, Response<SensorResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String status = response.body().getStatus();
                    if ("ok".equals(status)) {
                        tvLog.append("\n[서버 응답: 정상] " + deviceName + " RSSI " + rssi + " - " + response.body().getResult() + " - " + response.body().getMessage());
                    } else if ("out_of_range".equals(status)) {
                        tvLog.append("\n[서버 응답: 범위 초과] " + deviceName + " RSSI " + rssi + " - " + response.body().getResult() + " - " + response.body().getMessage());
                    } else {
                        tvLog.append("\n[서버 응답] " + deviceName + " RSSI " + rssi + " - " + response.body().getResult() + " - " + response.body().getMessage());
                    }
                } else {
                    String errorDetail;
                    try {
                        errorDetail = response.errorBody() != null ? response.errorBody().string() : "(본문 없음)";
                    } catch (IOException e) {
                        errorDetail = "(에러 본문 읽기 실패: " + e.getMessage() + ")";
                    }
                    tvLog.append("\n[서버 응답 오류] " + deviceName + " RSSI " + rssi + " - HTTP " + response.code() + " - " + errorDetail);
                }
            }

            @Override
            public void onFailure(Call<SensorResponse> call, Throwable t) {
                tvLog.append("\n[전송 실패] " + deviceName + " RSSI " + rssi + " - " + t.getMessage());
            }
        });
    }
}