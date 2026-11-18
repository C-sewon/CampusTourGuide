package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 장소 안내 화면 (프로토타입 "장소 안내", 설계 문서 4장 "장소 카드 화면").
 * 코스의 장소를 하나씩 보여준다: 도착 배너, 이름, 방문 상태, 지금 온도와 출처·측정 시각, 소개, 다음 장소.
 *
 * 도착은 지금은 "도착" 버튼으로만 체크한다.
 * BLE 자동 도착은 지유님 스캔 기능이 "센서 N 도착"을 알려주면 onSensorArrived(N) 을 부르도록 연결할 예정이다.
 */
public class SpotActivity extends AppCompatActivity {

    private static final String EXTRA_COURSE_ID = "course_id";
    private static final String STATE_INDEX = "index";

    /** 코스의 아직 안 간 첫 장소부터 안내한다 */
    public static void start(Context context, int courseId) {
        context.startActivity(new Intent(context, SpotActivity.class).putExtra(EXTRA_COURSE_ID, courseId));
    }

    private TextView tvProgress, tvBannerTitle, tvBannerSub, tvSpotName, tvVisit;
    private TextView tvTemp, tvTempSource, tvSpotDesc, tvNextName, tvNextWalk;
    private LinearLayout rowBanner, cardTemp;
    private FrameLayout bannerIconBg, tempTile;
    private ImageView bannerIcon, tempIcon;
    private MaterialButton btnArrive, btnNext;
    private ScrollView scroll;
    private MapView mapView;
    private ImageView ivIllustration;
    private TextView tvDistance;

    /** 내 위치 (휴대폰 GPS·와이파이 위치). 권한이 없거나 아직 못 잡았으면 null */
    private FusedLocationProviderClient locationClient;
    private GeoPoint myLocation;
    /** 다음 지도 그리기에서 범위를 맞출지. 장소가 바뀌거나 처음 위치를 잡았을 때만 맞추고, 그 뒤엔 사용자가 옮긴 지도를 그대로 둔다 */
    private boolean fitPending = true;
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            Location last = result.getLastLocation();
            if (last == null || course == null) return;
            if (myLocation == null) fitPending = true;
            myLocation = new GeoPoint(last.getLatitude(), last.getLongitude());
            showMap();
        }
    };
    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> startLocationUpdates());

    private HomeApi api;
    private HomeApi.Course course;
    private int index = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TourMap.configure(this);
        setContentView(R.layout.activity_spot);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));

        tvProgress = findViewById(R.id.tvProgress);
        tvBannerTitle = findViewById(R.id.tvBannerTitle);
        tvBannerSub = findViewById(R.id.tvBannerSub);
        tvSpotName = findViewById(R.id.tvSpotName);
        tvVisit = findViewById(R.id.tvVisit);
        tvTemp = findViewById(R.id.tvTemp);
        tvTempSource = findViewById(R.id.tvTempSource);
        tvSpotDesc = findViewById(R.id.tvSpotDesc);
        tvNextName = findViewById(R.id.tvNextName);
        tvNextWalk = findViewById(R.id.tvNextWalk);
        rowBanner = findViewById(R.id.rowBanner);
        cardTemp = findViewById(R.id.cardTemp);
        bannerIconBg = findViewById(R.id.bannerIconBg);
        tempTile = findViewById(R.id.tempTile);
        bannerIcon = findViewById(R.id.bannerIcon);
        tempIcon = findViewById(R.id.tempIcon);
        btnArrive = findViewById(R.id.btnArrive);
        btnNext = findViewById(R.id.btnNext);
        scroll = findViewById(R.id.scroll);
        mapView = findViewById(R.id.mapView);
        ivIllustration = findViewById(R.id.ivIllustration);
        tvDistance = findViewById(R.id.tvDistance);
        TourMap.setup(this, mapView);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        // 위치 권한은 한 번만 묻는다. 거절하면 이전 장소에서 선을 긋는다
        if (savedInstanceState == null && !hasLocationPermission()) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }

        if (savedInstanceState != null) index = savedInstanceState.getInt(STATE_INDEX, -1);

        // 뒤로: 이전 장소로, 첫 장소면 화면을 닫는다 (프로토타입과 같음)
        findViewById(R.id.btnBack).setOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                back();
            }
        });
        btnArrive.setOnClickListener(v -> markArrived(true));
        btnNext.setOnClickListener(v -> {
            if (course != null && index == course.spots.size() - 1) finishTour();
            else move(1);
        });

        load(getIntent().getIntExtra(EXTRA_COURSE_ID, -1));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_INDEX, index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        locationClient.removeLocationUpdates(locationCallback);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 3초마다 내 위치를 받는다. 권한이 없으면 아무것도 하지 않는다 */
    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        if (!hasLocationPermission() || isFinishing()) return;
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(2000)
                .build();
        locationClient.removeLocationUpdates(locationCallback);
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    // ---------- 데이터 불러오기 ----------

    private void load(int courseId) {
        api = TourServer.api(this);
        if (api == null) {
            setCourse(TourSamples.course(courseId));
            return;
        }
        HomeApi requestApi = api;
        api.courses().enqueue(new Callback<List<HomeApi.Course>>() {
            @Override
            public void onResponse(Call<List<HomeApi.Course>> call, Response<List<HomeApi.Course>> res) {
                if (isFinishing() || isDestroyed() || requestApi != api) return;
                HomeApi.Course found = null;
                if (res.isSuccessful() && res.body() != null) {
                    for (HomeApi.Course c : res.body()) if (c.id == courseId) found = c;
                }
                setCourse(found);
            }

            @Override
            public void onFailure(Call<List<HomeApi.Course>> call, Throwable t) {
                if (isFinishing() || isDestroyed() || requestApi != api) return;
                close("서버에 연결할 수 없어요");
            }
        });
    }

    private void setCourse(HomeApi.Course c) {
        if (c == null || c.spots == null || c.spots.isEmpty()) {
            close("코스를 찾을 수 없어요");
            return;
        }
        course = c;
        if (index < 0) index = TourProgress.firstUnvisited(c.spots);
        index = Math.max(0, Math.min(index, c.spots.size() - 1));
        show();
    }

    private void close(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------- 화면 그리기 ----------

    private void show() {
        int total = course.spots.size();
        HomeApi.Spot spot = course.spots.get(index).spot;

        tvProgress.setText(SpotDisplay.progress(index, total));
        tvSpotName.setText(spot.name);
        tvSpotDesc.setText(spot.description == null || spot.description.isEmpty()
                ? "아직 소개 글이 없어요." : spot.description);

        showBanner(spot);
        fitPending = true;
        showMap();
        showVisit(spot);
        showTemperature(spot);
        showNext();
        showButtons(spot);
        scroll.scrollTo(0, 0);
    }

    private void showBanner(HomeApi.Spot spot) {
        String method = spot.arrival != null ? spot.arrival.method : null;
        boolean arrived = TourProgress.isVisited(spot.id);
        boolean manual = TourProgress.isManual(spot.id);
        SpotDisplay.Banner banner = SpotDisplay.banner(method, arrived);

        tvBannerTitle.setText(SpotDisplay.bannerTitle(banner, manual));
        tvBannerSub.setText(SpotDisplay.bannerSub(banner, manual));
        switch (banner) {
            case WAITING:
                rowBanner.setBackgroundResource(R.drawable.bg_tour_banner_wait);
                rowBanner.setBackgroundTintList(null);
                setCircleIcon(R.color.tour_blue_bg, R.drawable.ic_tour_bt, R.color.tour_blue);
                break;
            case MANUAL_NEEDED:
                rowBanner.setBackgroundResource(R.drawable.bg_tour_banner_manual);
                rowBanner.setBackgroundTintList(null);
                setCircleIcon(R.color.tour_mint, R.drawable.ic_tour_hand, R.color.tour_primary);
                break;
            default:
                rowBanner.setBackgroundResource(R.drawable.bg_tour_round16);
                rowBanner.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tour_mint));
                setCircleIcon(R.color.tour_primary, R.drawable.ic_tour_tick, R.color.tour_on_primary);
        }
    }

    /**
     * 지도: 내 위치(파란 점) → 지금 가야 할 장소(초록 번호)를 직선으로 잇고, 선 가운데에 진행 방향 화살표, 위에 남은 거리.
     * 내 위치를 모르면(권한 거절, 실내 등) 이전 장소(회색 번호)에서 선을 긋는다. 첫 장소면 가야 할 장소만.
     * 가야 할 장소 좌표를 모르면 건물 그림. 길을 따라가는 경로가 아니라 직선이다 (경로 계산 서비스가 필요해서).
     */
    private void showMap() {
        List<Overlay> old = new ArrayList<>();
        for (Overlay o : mapView.getOverlays()) if (o instanceof Marker || o instanceof Polyline) old.add(o);
        mapView.getOverlays().removeAll(old);

        GeoPoint target = point(course.spots.get(index).spot);
        if (target == null) {
            mapView.setVisibility(View.INVISIBLE);
            ivIllustration.setVisibility(View.VISIBLE);
            tvDistance.setVisibility(View.GONE);
            return;
        }
        mapView.setVisibility(View.VISIBLE);
        ivIllustration.setVisibility(View.GONE);

        float density = getResources().getDisplayMetrics().density;
        int primary = ContextCompat.getColor(this, R.color.tour_primary);
        boolean fromMe = myLocation != null;
        GeoPoint from = fromMe ? myLocation : (index > 0 ? point(course.spots.get(index - 1).spot) : null);

        if (fromMe) {
            tvDistance.setText(SpotDisplay.distance(myLocation.distanceToAsDouble(target)));
            tvDistance.setVisibility(View.VISIBLE);
        } else {
            tvDistance.setVisibility(View.GONE);
        }
        if (from != null && from.distanceToAsDouble(target) < 1) from = null;  // 같은 자리면 선을 긋지 않는다

        if (from != null) {
            // 흰 테두리 + 초록 선 (지도 위에서 잘 보이게 두 번 그린다)
            mapView.getOverlays().add(line(from, target, 0xFFFFFFFF, 9 * density));
            mapView.getOverlays().add(line(from, target, ContextCompat.getColor(this, R.color.tour_bar), 5 * density));

            Marker arrow = new Marker(mapView);
            arrow.setPosition(new GeoPoint((from.getLatitude() + target.getLatitude()) / 2,
                    (from.getLongitude() + target.getLongitude()) / 2));
            arrow.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            arrow.setIcon(TourMap.arrowIcon(this, from.bearingTo(target), ContextCompat.getColor(this, R.color.tour_bar)));
            arrow.setInfoWindow(null);
            mapView.getOverlays().add(arrow);

            if (!fromMe) mapView.getOverlays().add(spotMarker(from, String.valueOf(index), 0xFF9AA0A6));
        }
        mapView.getOverlays().add(spotMarker(target, String.valueOf(index + 1), primary));
        if (fromMe) {
            Marker me = new Marker(mapView);
            me.setPosition(myLocation);
            me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            me.setIcon(TourMap.myLocationIcon(this));
            me.setInfoWindow(null);
            me.setOnMarkerClickListener((m, map) -> true);
            mapView.getOverlays().add(me);
        }

        if (fitPending) {
            fitPending = false;
            if (from == null) {
                mapView.getController().setZoom(18.0);
                mapView.getController().setCenter(target);
            } else {
                BoundingBox box = BoundingBox.fromGeoPoints(Arrays.asList(from, target));
                int padding = Math.round(44 * density);
                if (mapView.getWidth() > 0) mapView.zoomToBoundingBox(box, false, padding);
                else mapView.addOnFirstLayoutListener((v, l, t, r, b) -> mapView.zoomToBoundingBox(box, false, padding));
            }
        }
        mapView.invalidate();
    }

    private Polyline line(GeoPoint from, GeoPoint to, int color, float width) {
        Polyline line = new Polyline(mapView);
        line.setPoints(Arrays.asList(from, to));
        Paint paint = line.getOutlinePaint();
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        line.setInfoWindow(null);
        return line;
    }

    private Marker spotMarker(GeoPoint point, String label, int color) {
        Marker marker = new Marker(mapView);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setIcon(TourMap.circleIcon(this, label, color));
        marker.setInfoWindow(null);
        marker.setOnMarkerClickListener((m, map) -> true);
        return marker;
    }

    private static GeoPoint point(HomeApi.Spot spot) {
        if (spot == null || spot.latitude == null || spot.longitude == null) return null;
        return new GeoPoint(spot.latitude, spot.longitude);
    }

    private void setCircleIcon(int bgColor, int iconRes, int iconColor) {
        bannerIconBg.setBackgroundTintList(ContextCompat.getColorStateList(this, bgColor));
        bannerIcon.setImageResource(iconRes);
        bannerIcon.setColorFilter(ContextCompat.getColor(this, iconColor));
    }

    /** 방문 완료(흰 바탕 + 테두리 + 체크) / 미방문(주황) */
    private void showVisit(HomeApi.Spot spot) {
        boolean visited = TourProgress.isVisited(spot.id);
        tvVisit.setText(visited ? "방문 완료" : "미방문");
        if (visited) {
            tvVisit.setBackgroundResource(R.drawable.bg_tour_chip_line);
            tvVisit.setBackgroundTintList(null);
        } else {
            tvVisit.setBackgroundResource(R.drawable.bg_tour_chip);
            tvVisit.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tour_warm_bg));
        }
        int fg = ContextCompat.getColor(this, visited ? R.color.tour_primary : R.color.tour_warm_chip_text);
        tvVisit.setTextColor(fg);
        TourStyle.setStartIcon(tvVisit, visited ? R.drawable.ic_tour_tick : 0, fg, 13);
    }

    /** 온도 카드: 이 장소 센서 / 근처 센서 / 오래된 값 / 값 없음 (프로토타입 4가지 상태) */
    private void showTemperature(HomeApi.Spot spot) {
        HomeApi.Reading r = spot.latestReading;
        int secondary = ContextCompat.getColor(this, R.color.tour_text_secondary);

        if (r == null || r.isSuspect) {
            cardTemp.setBackgroundResource(R.drawable.bg_tour_temp_none);
            cardTemp.setElevation(0);
            tempTile.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tour_none_tile));
            tempIcon.setImageResource(R.drawable.ic_tour_off);
            tempIcon.setColorFilter(secondary);
            tvTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvTemp.setTextColor(ContextCompat.getColor(this, R.color.tour_text));
            tvTemp.setText(r == null ? "지금은 이 근처 센서 값을\n받을 수 없어요" : "지금은 이 장소 센서 값을\n확인하고 있어요");
            tvTempSource.setVisibility(View.GONE);
            return;
        }

        cardTemp.setBackgroundResource(R.drawable.bg_tour_card);
        cardTemp.setElevation(getResources().getDisplayMetrics().density);
        tempTile.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tour_warm_bg));
        tempIcon.setImageResource(R.drawable.ic_tour_therm);
        tempIcon.setColorFilter(ContextCompat.getColor(this, R.color.tour_therm));
        tvTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        tvTemp.setText(HomeDisplay.temperature(r.temperature));
        tvTempSource.setVisibility(View.VISIBLE);

        HomeApi.ValueSource vs = spot.valueSource;
        boolean onSite = vs == null || vs.isOnSite;
        if (r.isStale) {
            tvTemp.setTextColor(secondary);
            tvTempSource.setText(SpotDisplay.staleSource(r.ageMinutes));
            int warm = ContextCompat.getColor(this, R.color.tour_warm_chip_text);
            tvTempSource.setTextColor(warm);
            TourStyle.setStartIcon(tvTempSource, R.drawable.ic_tour_clock, ContextCompat.getColor(this, R.color.tour_warm_icon), 14);
        } else if (onSite) {
            tvTemp.setTextColor(ContextCompat.getColor(this, R.color.tour_text));
            tvTempSource.setText(SpotDisplay.source(true, null, r.ageMinutes));
            tvTempSource.setTextColor(secondary);
            TourStyle.setStartIcon(tvTempSource, 0, 0, 0);
        } else {
            tvTemp.setTextColor(ContextCompat.getColor(this, R.color.tour_text));
            tvTempSource.setText(SpotDisplay.source(false, vs.distanceM, r.ageMinutes));
            int primary = ContextCompat.getColor(this, R.color.tour_primary);
            tvTempSource.setTextColor(primary);
            TourStyle.setStartIcon(tvTempSource, R.drawable.ic_tour_pin, primary, 14);
        }
    }

    private void showNext() {
        boolean isLast = index == course.spots.size() - 1;
        if (isLast) {
            tvNextName.setText("마지막 장소예요");
            tvNextWalk.setVisibility(View.GONE);
        } else {
            HomeApi.CourseSpot next = course.spots.get(index + 1);
            tvNextName.setText(next.spot.name);
            tvNextWalk.setText(SpotDisplay.walk(next.minutes));
            tvNextWalk.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 도착 버튼으로 체크하는 장소에 아직 안 왔으면 "도착"을 크게(채운 버튼), "다음"을 테두리로.
     * 그 밖에는 "도착"을 테두리(도착했으면 "도착 완료"로 비활성), "다음"을 크게.
     */
    private void showButtons(HomeApi.Spot spot) {
        boolean arrived = TourProgress.isVisited(spot.id);
        boolean manualFirst = spot.arrival != null && "manual".equals(spot.arrival.method) && !arrived;
        boolean isLast = index == course.spots.size() - 1;

        btnArrive.setText(SpotDisplay.arriveLabel(arrived));
        btnNext.setText(SpotDisplay.nextLabel(isLast));
        if (manualFirst) {
            TourStyle.styleFilled(this, btnArrive);
            btnArrive.setIconResource(R.drawable.ic_tour_hand);
            TourStyle.styleOutlined(this, btnNext, true);
            setWeight(btnArrive, 1.25f);
            setWeight(btnNext, 1f);
        } else {
            TourStyle.styleOutlined(this, btnArrive, !arrived);
            btnArrive.setIcon(null);
            TourStyle.styleFilled(this, btnNext);
            setWeight(btnArrive, 0.8f);
            setWeight(btnNext, 1.2f);
        }
    }

    private static void setWeight(View view, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        lp.weight = weight;
        view.setLayoutParams(lp);
    }

    // ---------- 동작 ----------

    private void markArrived(boolean manual) {
        if (course == null) return;
        HomeApi.Spot spot = course.spots.get(index).spot;
        if (!TourProgress.markArrived(spot.id, manual)) return;
        show();
        if (manual) Toast.makeText(this, "도착 체크했어요", Toast.LENGTH_SHORT).show();
    }

    /**
     * BLE 자동 도착 연결 자리. 지유님 스캔 기능이 "센서 N 도착"을 알려주면 부른다.
     * 지금 안내 중인 장소에 설치된 센서면 도착으로 체크한다.
     */
    public void onSensorArrived(int sensorId) {
        if (course == null) return;
        HomeApi.Spot spot = course.spots.get(index).spot;
        if (spot.arrival == null || spot.arrival.sensors == null) return;
        for (HomeApi.SpotSensor s : spot.arrival.sensors) {
            if (s.sensorId == sensorId) {
                markArrived(false);
                return;
            }
        }
    }

    private void move(int delta) {
        if (course == null) return;
        int next = index + delta;
        if (next < 0 || next >= course.spots.size()) return;
        index = next;
        show();
    }

    private void back() {
        if (course != null && index > 0) move(-1);
        else finish();
    }

    /** 내 투어 화면이 생기면 그리로 보낸다. 지금은 결과만 알려주고 닫는다 */
    private void finishTour() {
        int total = course.spots.size();
        int visited = TourProgress.countVisited(course.spots);
        Toast.makeText(this, "투어를 마쳤어요! " + total + "곳 중 " + visited + "곳 방문", Toast.LENGTH_LONG).show();
        finish();
    }
}
