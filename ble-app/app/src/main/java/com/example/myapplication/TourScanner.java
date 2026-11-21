package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 앱 전체에서 하나만 도는 BLE 스캐너. (#29)
 *
 * 스캔, 센서 거르기, 13바이트 읽기, 도착 판정(ArrivalDetector), 업로드(LabUploader)를 맡는다.
 * 화면은 Listener로 "센서 N 도착", "장소 도착", "스캔 상태"를 받는다.
 *
 * 켜고 끄기
 * - 투어 화면은 onStart에서 acquire(), onStop에서 release()를 부른다. 하나라도 켜져 있으면 스캔한다. (ScanLease)
 * - 앱이 뒤로 가면 3초 뒤 멈춘다. 백그라운드 BLE 제한 때문에 앱이 화면에 있는 동안만 동작한다. (NRF-03)
 *
 * 도착 기록 (#37)
 * - 센서 N 도착 → 그 센서가 설치된 모든 장소(ArrivalRouter)를 TourProgress에 자동 도착으로 기록한다.
 *   지금 안내 중인 장소가 아니어도 기록한다. 이미 기록된 장소는 TourProgress가 무시한다. (같은 장소는 한 번만)
 * - 새로 기록된 장소만 onSpotArrived로 알린다. 카드를 띄울지는 화면이 정한다. (has_card)
 *
 * 모든 메서드와 알림은 메인 스레드에서 불린다.
 */
public final class TourScanner {

    /** 화면이 받는 알림. 필요한 것만 구현한다 */
    public interface Listener {
        default void onStateChanged(ScanState state) {}
        /** 장소가 새로 자동 도착으로 기록됐다 */
        default void onSpotArrived(int spotId, int sensorId) {}
        default void onSensorArrive(int sensorId) {}
        default void onSensorLeave(int sensorId) {}
        /** 우리 센서의 광고를 받았다 (개발용 로그) */
        default void onPacket(String name, String mac, int rssi, SensorPacket packet, byte[] raw) {}
        /** 로그 한 줄 (업로드 결과 등, 개발용) */
        default void onLog(String line) {}
    }

    private static final ParcelUuid SERVICE_UUID = ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    /** 이보다 약한 신호(먼 센서/노이즈)는 무시 */
    private static final int MIN_VALID_RSSI = -100;
    /** 신호가 아예 안 들어와도 "떠남"을 알 수 있도록 2초마다 확인 */
    private static final long ARRIVAL_TICK_MS = 2000;

    private static TourScanner instance;

    public static synchronized TourScanner get(Context context) {
        if (instance == null) instance = new TourScanner(context.getApplicationContext());
        return instance;
    }

    /** 스캔에 필요한 권한. 안드로이드 12(S) 이상은 블루투스 스캔·연결, 그 아래는 위치 */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean hasPermissions(Context context) {
        for (String p : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrivalDetector detector;
    private final LabUploader uploader;
    private final ScanLease lease;

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private ScanState state = ScanState.IDLE;

    private final Runnable arrivalTick = new Runnable() {
        @Override
        public void run() {
            detector.tick(System.currentTimeMillis());
            main.postDelayed(this, ARRIVAL_TICK_MS);
        }
    };

    private TourScanner(Context app) {
        this.app = app;
        this.uploader = new LabUploader(app, line -> { for (Listener l : listeners) l.onLog(line); });
        this.detector = new ArrivalDetector(new ArrivalDetector.Listener() {
            @Override
            public void onArrive(int sensorId) {
                for (Listener l : listeners) l.onSensorArrive(sensorId);
                recordSensorArrival(sensorId);
            }

            @Override
            public void onLeave(int sensorId) {
                for (Listener l : listeners) l.onSensorLeave(sensorId);
            }
        });
        this.lease = new ScanLease(this::startScan, this::stopScan, new ScanLease.Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                main.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object token) {
                main.removeCallbacks((Runnable) token);
            }
        });
    }

    // ---------- 화면이 부르는 것 ----------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 스캔이 필요한 화면이 켜졌다 (onStart) */
    public void acquire() {
        lease.acquire();
    }

    /** 스캔이 필요한 화면이 꺼졌다 (onStop) */
    public void release() {
        lease.release();
    }

    /** 권한을 받았거나 블루투스를 켜고 돌아왔을 때 부른다. 스캔이 필요한데 멈춰 있으면 다시 시작한다 */
    public void refresh() {
        if (lease.isRunning() && !scanning) startScan();
    }

    public ScanState state() {
        return state;
    }

    public LabUploader uploader() {
        return uploader;
    }

    /**
     * 센서 N 도착을 장소 도착으로 기록한다. 안내 중인 장소가 아니어도 기록한다. (#37)
     * 새로 기록된 장소만 onSpotArrived로 알린다.
     */
    public void recordSensorArrival(int sensorId) {
        for (int spotId : ArrivalRouter.spotIdsForSensor(sensorId)) {
            if (TourProgress.markArrived(spotId, false)) {
                for (Listener l : listeners) l.onSpotArrived(spotId, sensorId);
            }
        }
    }

    // ---------- 스캔 ----------

    private void startScan() {
        if (scanning) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setState(ScanState.UNSUPPORTED);
            return;
        }
        if (!hasPermissions(app)) {
            setState(ScanState.NO_PERMISSION);
            return;
        }
        if (!adapter.isEnabled()) {
            setState(ScanState.BLUETOOTH_OFF);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setState(ScanState.BLUETOOTH_OFF);
            return;
        }
        // ScanFilter.setServiceUuid()는 "서비스 UUID 목록" 필드만 검사해서, Service Data에만 UUID를 싣는 센서는 걸리지 않는다.
        // 그래서 OS 필터는 걸지 않고 onScanResult에서 이름·MAC으로 거른다.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, callback);
        } catch (SecurityException e) {
            setState(ScanState.NO_PERMISSION);
            return;
        }
        scanning = true;
        main.removeCallbacks(arrivalTick);
        main.postDelayed(arrivalTick, ARRIVAL_TICK_MS);
        setState(ScanState.SCANNING);
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(callback);
            } catch (SecurityException | IllegalStateException ignored) {
                // 권한이 사라졌거나 블루투스가 꺼진 경우. 어차피 멈춘 상태다
            }
        }
        scanning = false;
        main.removeCallbacks(arrivalTick);
        detector.reset();
        setState(ScanState.IDLE);
    }

    private void setState(ScanState s) {
        if (state == s) return;
        state = s;
        for (Listener l : listeners) l.onStateChanged(s);
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            int rssi = result.getRssi();

            String name;
            try {
                name = device.getName();
            } catch (SecurityException e) {
                return; // 안드로이드 12 이상에서 연결 권한이 없음
            }
            String mac = device.getAddress();

            // 우리 센서 5대만 (MAC 우선, 없으면 이름). 이름이 잠깐 비어 있어도 MAC으로 찾은 센서 이름을 쓴다
            Sensors.Sensor sensor = Sensors.find(name, mac);
            if (sensor == null) return;
            name = sensor.bleName;
            if (rssi == 0 || rssi < MIN_VALID_RSSI) return;

            // 도착 판정에는 신호 세기만 필요하므로 값 읽기와 상관없이 먼저 넘긴다
            detector.update(sensor.id, rssi, System.currentTimeMillis());

            byte[] raw = record != null ? record.getServiceData(SERVICE_UUID) : null;
            if (raw == null) return;
            SensorPacket packet = SensorPacket.parse(raw);
            if (packet == null) return;

            for (Listener l : listeners) l.onPacket(name, mac, rssi, packet, raw);
            uploader.onPacket(packet, mac, rssi, name);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            main.removeCallbacks(arrivalTick);
            for (Listener l : listeners) l.onLog("스캔 에러 발생 코드: " + errorCode);
            setState(ScanState.FAILED);
        }
    };
}
