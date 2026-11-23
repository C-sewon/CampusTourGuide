package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 홈(탐색) 화면 (설계 문서 4장 "화면 1").
 * 서버 주소가 설정돼 있으면 GET /api/sensors(/status), /api/courses/recommend 로 채우고,
 * 없으면 [샘플] 데이터로 화면 모양만 보여준다.
 * 서버 주소는 앱 제목을 길게 눌러 넣는다 (개발용, TourServer).
 */
public class HomeActivity extends AppCompatActivity {

    /** 지도 처음 중심: 센서 5대가 모여 있는 곳 */
    private static final GeoPoint MAP_CENTER = new GeoPoint(36.6296, 127.4562);
    private static final double MAP_ZOOM = 17.0;

    private TextView tvRecommendTitle, tvReason, tvRecommendMeta;
    private View rowReason;
    private ImageView ivReason;
    private MapView mapView;
    private HomeApi api;
    /** 투어 시작하기 버튼이 여는 코스. 추천을 받기 전에는 null */
    private Integer recommendedCourseId;

    /** 지도 범위를 맞출 때 쓰는 우리 팀(opensrc) 센서 위치. 연구실 센서는 캠퍼스 곳곳에 있어 범위에서 뺀다 */
    private final List<GeoPoint> tourSensorPoints = new ArrayList<>();

    // ---------- BLE 자동 감지 (#29, 황지유) ----------
    private TextView tvAutoDetect, tvAutoDetectBanner;
    private final ActivityResultLauncher<String[]> blePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> TourScanner.get(this).refresh());
    private final TourScanner.Listener scanListener = new TourScanner.Listener() {
        @Override
        public void onStateChanged(ScanState state) {
            showScanState(state);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TourMap.configure(this);

        setContentView(R.layout.activity_home);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));

        tvRecommendTitle = findViewById(R.id.tvRecommendTitle);
        tvReason = findViewById(R.id.tvReason);
        tvRecommendMeta = findViewById(R.id.tvRecommendMeta);
        rowReason = findViewById(R.id.rowReason);
        ivReason = findViewById(R.id.ivReason);
        mapView = findViewById(R.id.mapView);

        findViewById(R.id.btnStartTour).setOnClickListener(v -> {
            if (recommendedCourseId == null) toast("추천 코스를 불러온 뒤에 시작할 수 있어요");
            else SpotActivity.start(this, recommendedCourseId);
        });
        // 추천 카드를 누르면 코스 둘러보기 (추천 코스를 골라 둔 상태)
        findViewById(R.id.cardRecommend).setOnClickListener(v -> CourseActivity.open(this, recommendedCourseId));
        TourTabs.setup(this, TourTabs.Tab.EXPLORE);
        findViewById(R.id.tvAppTitle).setOnLongClickListener(v -> {
            TourServer.askUrl(this, this::load);
            return true;
        });

        setupMap();
        load();
        setupAutoDetect(savedInstanceState == null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TourScanner scanner = TourScanner.get(this);
        scanner.addListener(scanListener);
        scanner.acquire();
        showScanState(scanner.state());
    }

    @Override
    protected void onStop() {
        super.onStop();
        TourScanner scanner = TourScanner.get(this);
        scanner.release();
        scanner.removeListener(scanListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        // 블루투스를 켜고 돌아왔거나 권한을 설정에서 허용한 경우 스캔을 다시 시작한다
        TourScanner.get(this).refresh();
    }

    // ---------- BLE 자동 감지 (#29, 황지유) ----------

    /** 처음 열 때 블루투스·위치 권한을 묻고, "자동 감지" 칩을 누르면 문제를 해결하는 화면으로 보낸다 */
    private void setupAutoDetect(boolean firstLaunch) {
        tvAutoDetect = findViewById(R.id.tvAutoDetect);
        tvAutoDetectBanner = findViewById(R.id.tvAutoDetectBanner);
        if (firstLaunch && !TourScanner.hasPermissions(this)) {
            blePermissionLauncher.launch(TourScanner.requiredPermissions());
        }
        View.OnClickListener fix = v -> {
            ScanState s = TourScanner.get(this).state();
            if (s == ScanState.NO_PERMISSION) blePermissionLauncher.launch(TourScanner.requiredPermissions());
            else if (s == ScanState.BLUETOOTH_OFF) startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        };
        tvAutoDetect.setOnClickListener(fix);
        tvAutoDetectBanner.setOnClickListener(fix);
        // 개발용: 칩을 길게 누르면 센서 로그 화면 (스캔·도착·업로드 로그)
        tvAutoDetect.setOnLongClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            return true;
        });
    }

    private void showScanState(ScanState state) {
        if (tvAutoDetect == null) return;
        tvAutoDetect.setText(state.label);
        tvAutoDetectBanner.setText(state.needsUserAction() ? state.label + " (눌러서 설정)" : state.label);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    private void setupMap() {
        TourMap.setup(this, mapView);
        mapView.getController().setZoom(MAP_ZOOM);
        mapView.getController().setCenter(MAP_CENTER);
    }

    // ---------- 데이터 불러오기 ----------

    /** 서버 주소가 있으면 서버에서, 없으면 샘플로 화면을 채운다 */
    private void load() {
        clearSensors();
        recommendedCourseId = null;
        api = TourServer.api(this);
        if (api == null) {
            showSample();
            return;
        }
        showLoading();
        loadSensors(api);
        loadRecommendation(api);
    }

    /** 센서 위치(/api/sensors)를 받은 뒤 최신 값(/api/sensors/status)을 받아 지도에 그린다 */
    private void loadSensors(HomeApi api) {
        api.sensors().enqueue(new Callback<List<HomeApi.Sensor>>() {
            @Override
            public void onResponse(Call<List<HomeApi.Sensor>> call, Response<List<HomeApi.Sensor>> res) {
                if (isGone(api)) return;
                if (!res.isSuccessful() || res.body() == null) {
                    toast("센서 목록을 받지 못했어요 (" + res.code() + ")");
                    return;
                }
                List<HomeApi.Sensor> sensors = res.body();
                api.sensorStatus().enqueue(new Callback<List<HomeApi.SensorStatus>>() {
                    @Override
                    public void onResponse(Call<List<HomeApi.SensorStatus>> c,
                                           Response<List<HomeApi.SensorStatus>> r) {
                        if (isGone(api)) return;
                        Map<Integer, HomeApi.Reading> latest = new HashMap<>();
                        if (r.isSuccessful() && r.body() != null) {
                            for (HomeApi.SensorStatus s : r.body()) latest.put(s.sensorId, s.latestReading);
                        }
                        showSensors(sensors, latest);
                    }

                    @Override
                    public void onFailure(Call<List<HomeApi.SensorStatus>> c, Throwable t) {
                        if (isGone(api)) return;
                        // 값은 못 받았어도 위치는 보여준다
                        showSensors(sensors, new HashMap<>());
                    }
                });
            }

            @Override
            public void onFailure(Call<List<HomeApi.Sensor>> call, Throwable t) {
                if (isGone(api)) return;
                toast("서버에 연결할 수 없어요. 앱 제목을 길게 눌러 주소를 확인해 주세요");
            }
        });
    }

    /** 추천(/api/courses/recommend)을 받고, 코스 목록(/api/courses)에서 장소 수를 센다 */
    private void loadRecommendation(HomeApi api) {
        api.recommend().enqueue(new Callback<HomeApi.Recommendation>() {
            @Override
            public void onResponse(Call<HomeApi.Recommendation> call, Response<HomeApi.Recommendation> res) {
                if (isGone(api)) return;
                HomeApi.Recommendation rec = res.body();
                if (!res.isSuccessful() || rec == null) {
                    showServerError("추천 코스를 받지 못했어요 (" + res.code() + ")");
                    return;
                }
                if (rec.recommendedCourseId == null) {
                    showNoCourse(rec);
                    return;
                }
                int minutes = 0;
                if (rec.courses != null) {
                    for (HomeApi.CourseChoice c : rec.courses) {
                        if (c.id == rec.recommendedCourseId) minutes = c.totalMinutes;
                    }
                }
                int totalMinutes = minutes;
                recommendedCourseId = rec.recommendedCourseId;
                showRecommendation(rec.recommendedCourseName, rec.condition, rec.temperature, totalMinutes, 0);

                api.courses().enqueue(new Callback<List<HomeApi.Course>>() {
                    @Override
                    public void onResponse(Call<List<HomeApi.Course>> c, Response<List<HomeApi.Course>> r) {
                        if (isGone(api) || !r.isSuccessful() || r.body() == null) return;
                        ArrivalRouter.update(r.body()); // 센서 N 도착 → 장소 (#29)
                        for (HomeApi.Course course : r.body()) {
                            if (course.id == rec.recommendedCourseId && course.spots != null) {
                                tvRecommendMeta.setText(HomeDisplay.meta(totalMinutes, course.spots.size()));
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<List<HomeApi.Course>> c, Throwable t) {
                        // 장소 수만 빠진다. 시간은 이미 보여주고 있다
                    }
                });
            }

            @Override
            public void onFailure(Call<HomeApi.Recommendation> call, Throwable t) {
                if (isGone(api)) return;
                showServerError("서버에 연결할 수 없어요");
            }
        });
    }

    /** 화면이 닫혔거나 서버 주소가 바뀌어 이 응답이 더는 필요 없으면 true */
    private boolean isGone(HomeApi requestApi) {
        return isFinishing() || isDestroyed() || requestApi != api;
    }

    // ---------- 지도 ----------

    private void showSensors(List<HomeApi.Sensor> sensors, Map<Integer, HomeApi.Reading> latest) {
        clearSensors();
        for (HomeApi.Sensor s : sensors) {
            if (s.latitude == null || s.longitude == null) continue;
            HomeApi.Reading r = latest.get(s.sensorId);
            boolean usable = r != null && !r.isStale && !r.isSuspect;
            String label = HomeDisplay.sensorLabel(s.sensorId,
                    s.location != null ? s.location : "위치 미상",
                    r != null, r != null ? r.temperature : 0, r != null ? r.ageMinutes : 0,
                    r != null && r.isStale, r != null && r.isSuspect);
            GeoPoint point = new GeoPoint(s.latitude, s.longitude);
            addSensorMarker(s.sensorId, point, usable ? r.temperature : null, label);
            if ("opensrc".equals(s.kind)) tourSensorPoints.add(point);
        }
        fitSensors();
    }

    /** [샘플] 화면 확인용 데이터. 센서 좌표는 서버 시드의 실제 값, 온도는 샘플 */
    private void showSample() {
        ArrivalRouter.update(TourSamples.courses()); // 센서 N 도착 → 장소 (#29)
        showRecommendation("[샘플] 짧은 코스", "hot", 27.0, 25, 3);
        recommendedCourseId = 1;

        addSampleSensor(1, "도서관 뒤편", 36.628895, 127.457839, 22.0);
        addSampleSensor(2, "사회과학대 입구", 36.62974, 127.456406, 28.0);
        addSampleSensor(3, "이마트24 건너편", 36.629309, 127.45748, null);
        addSampleSensor(4, "테니스장 앞", 36.63027, 127.455806, null);
        addSampleSensor(5, "테니스장 건너편", 36.630931, 127.454222, 31.0);
        fitSensors();
    }

    private void addSampleSensor(int sensorId, String location, double lat, double lon, Double celsius) {
        GeoPoint point = new GeoPoint(lat, lon);
        addSensorMarker(sensorId, point, celsius, "[샘플] " + HomeDisplay.sensorLabel(sensorId, location, celsius));
        tourSensorPoints.add(point);
    }

    /** 지도에 센서 점 하나를 그린다. 온도 색은 서버 추천 기준(27℃ / 5℃)과 같다. 값이 없으면 회색 */
    private void addSensorMarker(int sensorId, GeoPoint point, Double celsius, String label) {
        Marker marker = new Marker(mapView);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setIcon(TourMap.circleIcon(this, String.valueOf(sensorId), TourStyle.sensorColor(celsius)));
        marker.setOnMarkerClickListener((m, map) -> {
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            return true;
        });
        mapView.getOverlays().add(marker);
    }

    private void clearSensors() {
        List<Overlay> markers = new ArrayList<>();
        for (Overlay o : mapView.getOverlays()) {
            if (o instanceof Marker) markers.add(o);
        }
        mapView.getOverlays().removeAll(markers);
        tourSensorPoints.clear();
        mapView.invalidate();
    }

    /** 우리 팀 센서가 모두 보이도록 지도 범위를 맞춘다. 지도 크기가 정해진 뒤에 해야 정확하다. */
    private void fitSensors() {
        mapView.invalidate();
        if (tourSensorPoints.isEmpty()) return;
        if (tourSensorPoints.size() == 1) {
            mapView.getController().setCenter(tourSensorPoints.get(0));
            return;
        }
        BoundingBox box = BoundingBox.fromGeoPoints(new ArrayList<>(tourSensorPoints));
        int padding = Math.round(36 * getResources().getDisplayMetrics().density);
        if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
            mapView.zoomToBoundingBox(box, false, padding);
        } else {
            mapView.addOnFirstLayoutListener((v, l, t, r, b) ->
                    mapView.zoomToBoundingBox(box, false, padding));
        }
    }

    // ---------- 추천 코스 카드 ----------

    /** 추천 코스 카드 채우기. 인자는 추천 API 응답 값 그대로. spotCount 를 모르면 0 */
    private void showRecommendation(String courseName, String condition, Double celsius,
                                    int totalMinutes, int spotCount) {
        tvRecommendTitle.setText("추천 코스: " + courseName);
        tvReason.setText(HomeDisplay.reason(condition, celsius));
        tvRecommendMeta.setText(HomeDisplay.meta(totalMinutes, spotCount));
        applyTone(HomeDisplay.tone(condition));
    }

    private void showLoading() {
        tvRecommendTitle.setText("추천 코스를 불러오는 중…");
        tvReason.setText("지금 기온을 확인하고 있어요");
        tvRecommendMeta.setText("");
        applyTone(HomeDisplay.Tone.NEUTRAL);
    }

    private void showNoCourse(HomeApi.Recommendation rec) {
        tvRecommendTitle.setText("아직 추천할 코스가 없어요");
        tvReason.setText(HomeDisplay.reason(rec.condition, rec.temperature));
        tvRecommendMeta.setText("");
        applyTone(HomeDisplay.tone(rec.condition));
    }

    private void showServerError(String message) {
        tvRecommendTitle.setText("추천 코스를 불러오지 못했어요");
        tvReason.setText(message);
        tvRecommendMeta.setText("앱 제목을 길게 눌러 서버 주소를 확인해 주세요");
        applyTone(HomeDisplay.Tone.NEUTRAL);
    }

    private void applyTone(HomeDisplay.Tone tone) {
        TourStyle.applyTone(this, rowReason, tvReason, ivReason, tone);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

}
