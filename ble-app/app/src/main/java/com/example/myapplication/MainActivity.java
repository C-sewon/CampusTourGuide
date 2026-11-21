package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 개발용 "센서 로그" 화면. (#29)
 *
 * 스캔, 도착 판정, 업로드는 TourScanner가 앱 전체에서 맡고, 이 화면은 그 알림을 로그로 보여 준다.
 * 홈 화면의 "자동 감지" 칩을 길게 누르면 열린다.
 *
 * 버튼
 * - 스캔 시작 / 스캔 중지: 이 화면이 스캔을 붙잡거나 놓는다. 투어 화면이 켜져 있으면 그쪽 때문에 스캔은 계속될 수 있다.
 * - 저장: 받은 값을 CSV(ble_data.csv)로 저장한다.
 * - 전송: 마지막으로 받은 값을 바로 업로드한다. (10초 제한 없음)
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvLog;
    private TourScanner scanner;
    private boolean holding = false;

    /** 저장 버튼을 누를 때까지 모아 두는 CSV 줄 */
    private final List<String> pendingCsvData = new ArrayList<>();

    /** 전송 버튼용: 마지막으로 받은 패킷 */
    private SensorPacket lastPacket;
    private String lastMac;
    private int lastRssi;
    private String lastDeviceName;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    private final TourScanner.Listener listener = new TourScanner.Listener() {
        @Override
        public void onStateChanged(ScanState state) {
            append("\n\n--- 스캔 상태: " + state.label + " ---");
        }

        @Override
        public void onSensorArrive(int sensorId) {
            Sensors.Sensor s = Sensors.byId(sensorId);
            append("\n\n[도착] " + s + " - " + s.location);
        }

        @Override
        public void onSensorLeave(int sensorId) {
            append("\n\n[떠남] " + Sensors.byId(sensorId));
        }

        @Override
        public void onSpotArrived(int spotId, int sensorId) {
            append("\n[장소 도착 기록] 장소 " + spotId + " (센서 " + sensorId + ")");
        }

        @Override
        public void onPacket(String name, String mac, int rssi, SensorPacket packet, byte[] raw) {
            append("\n[수신] 이름: " + name + ", MAC: " + mac + ", RSSI: " + rssi + "\n" + packet);
            // 진단용: 원본 바이트를 그대로 남겨서 AQI/TVOC/eCO2 자리가 수신 시점에 0인지 확인할 수 있게 한다
            append("\nRAW(" + raw.length + "B): " + bytesToHex(raw)
                    + "\n  ㄴ aqi byte[4]=" + String.format("%02x", raw[4])
                    + ", tvoc byte[5-6]=" + String.format("%02x%02x", raw[6], raw[5])
                    + ", eco2 byte[7-8]=" + String.format("%02x%02x", raw[8], raw[7]));

            pendingCsvData.add(packet.timestamp + "," + name + "," + mac + "," + rssi + ",0x181A," + packet.eco2 + ","
                    + packet.temperature + "," + packet.humidity + "," + packet.aqi + "," + packet.tvoc + "\n");

            lastPacket = packet;
            lastMac = mac;
            lastRssi = rssi;
            lastDeviceName = name;
        }

        @Override
        public void onLog(String line) {
            append("\n" + line);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvLog = findViewById(R.id.tvLog);
        scanner = TourScanner.get(this);
        scanner.addListener(listener);
        append("\n--- 스캔 상태: " + scanner.state().label + " ---");

        Button btnScan = findViewById(R.id.btnScan);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnSend = findViewById(R.id.btnSend);

        btnScan.setOnClickListener(v -> startScanning());
        btnStop.setOnClickListener(v -> stopScanning());
        btnSave.setOnClickListener(v -> saveBufferedData());
        btnSend.setOnClickListener(v -> {
            if (lastPacket == null) {
                Toast.makeText(this, "전송할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            scanner.uploader().sendNow(lastPacket, lastMac, lastRssi, lastDeviceName);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 블루투스를 앱 실행 뒤에 켰거나 권한을 뒤늦게 허용한 경우를 대비해 다시 확인
        scanner.refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (holding) scanner.release();
        holding = false;
        scanner.removeListener(listener);
    }

    private void startScanning() {
        if (!TourScanner.hasPermissions(this)) {
            permissionLauncher.launch(TourScanner.requiredPermissions());
            return;
        }
        if (!holding) {
            holding = true;
            scanner.acquire();
        }
        scanner.refresh();
        append("\n\n--- 스캔 시작 요청 ---");
    }

    private void stopScanning() {
        if (holding) {
            holding = false;
            scanner.release();
            append("\n\n--- 스캔 중지 요청 (투어 화면이 켜져 있으면 스캔은 계속됨) ---");
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (TourScanner.hasPermissions(this)) {
            startScanning();
        } else {
            Toast.makeText(this, "블루투스/위치 권한이 없으면 스캔할 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void saveBufferedData() {
        if (pendingCsvData.isEmpty()) {
            Toast.makeText(this, "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(getExternalFilesDir(null), "ble_data.csv");
        boolean exists = file.exists();
        try (FileWriter fw = new FileWriter(file, true)) {
            if (!exists) fw.append("timestamp,device_name,device_address,rssi,uuid,co2,temperature,humidity,aqi,tvoc\n");
            for (String line : pendingCsvData) fw.append(line);
            int saved = pendingCsvData.size();
            pendingCsvData.clear();
            Toast.makeText(this, saved + "건의 데이터가 저장되었습니다.", Toast.LENGTH_SHORT).show();
            append("\n\n[알림] " + saved + "건의 데이터 CSV 저장 완료!");
        } catch (IOException e) {
            Toast.makeText(this, "저장 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void append(String text) {
        tvLog.append(text);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
