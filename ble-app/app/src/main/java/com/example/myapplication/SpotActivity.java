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
import java.util.List;


/**
 * 장소 안내 화면 (프로토타입 "장소 안내", 설계 문서 4장 "장소 카드 화면").
 * 코스의 장소를 하나씩 보여준다: 도착 배너, 이름, 방문 상태, 지금 온도와 출처·측정 시각, 소개, 다음 장소.
 *
 * 도착은 "도착" 버튼과 BLE 자동 도착 두 가지로 체크한다.
 * BLE 자동 도착은 TourScanner가 "센서 N 도착"을 받아 그 센서가 설치된 장소를 TourProgress에 기록하고,
 * 이 화면은 scanListener로 알림을 받아 다시 그린다. 안내 중인 장소가 아니어도 기록된다. (#29, #37)
 */
public class SpotActivity extends AppCompatActivity {

    private static final String EXTRA_COURSE_ID = "course_id";
    private static final String EXTRA_RESUME = "resume";
    private static final String STATE_INDEX = "index";

    /** 코스의 아직 안 간 첫 장소부터 안내한다. 내 코스는 방금 고른 코스라 첫 장소부터 */
    public static void start(Context context, int courseId) {
        context.startActivity(new Intent(context, SpotActivity.class).putExtra(EXTRA_COURSE_ID, courseId));
    }

    /** 내 투어의 "투어 이어서 하기": 내 코스도 아직 안 간 첫 장소부터 */
    public static void resume(Context context, int courseId) {
        context.startActivity(new Intent(context, SpotActivity.class)
                .putExtra(EXTRA_COURSE_ID, courseId).putExtra(EXTRA_RESUME, true));
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

    /** 걷는 길 경로 (TourRoute). 받은 경로의 출발점·도착점과 함께 기억해 두고, 많이 움직였을 때만 다시 받는다 */
    private TourRoute.Route route;
    private GeoPoint routeFrom, routeTarget;
    private okhttp3.Call routeCall;
    private GeoPoint routeCallTarget;
    private long lastRouteRequestMs;
    /** 요청 간격 때문에 미룬 경로 요청을 나중에 다시 시도한다 */
    private final Runnable routeRetry = this::retryRoute;
    /** 내 위치가 이만큼 움직이면 경로를 다시 받는다 (서비스에 요청을 너무 많이 보내지 않으려고) */
    private static final double ROUTE_REFRESH_M = 25;
    private static final long ROUTE_MIN_INTERVAL_MS = 5000;
    private static final long ROUTE_RETRY_MS = 15000;
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
    /** 카드를 정할 때 쓰는 전체 코스 목록 (핵심 코스, 카드 장소). TourData 에서 받는다 */
    private List<HomeApi.Course> allCourses;
    /** 지금 카드를 띄우고 있으면 true. 여러 장이면 닫힐 때 다음 카드를 띄운다 */
    private boolean cardShowing;

    /** BLE 자동 도착 (#29, 황지유). 어느 장소든 새로 도착이 기록되면 화면을 다시 그린다 */
    private final TourScanner.Listener scanListener = new TourScanner.Listener() {
        @Override
        public void onSpotArrived(int spotId, int sensorId) {
            if (course != null) show();
            showPendingCards();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TourProgress.init(this); // 방문 기록을 폰에서 읽어 온다 (#37). 이 화면부터 다시 열릴 수도 있어서 여기서도 부른다
        TourCards.init(this);    // 이미 띄운 카드 (#37)
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
    protected void onStart() {
        super.onStart();
        TourScanner scanner = TourScanner.get(this);
        scanner.addListener(scanListener);
        scanner.acquire();
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
        startLocationUpdates();
        TourScanner.get(this).refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        locationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (routeCall != null) routeCall.cancel();
        mapView.removeCallbacks(routeRetry);
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

    /**
     * 코스는 TourData 가 서버, 폰 저장본, 기본 목록 순서로 가져온다. (#37)
     * 서버가 안 돼도 장소, 길 안내, 도착 체크는 되고 온도만 "값을 받을 수 없어요"로 보인다.
     * 자동 도착 연결(ArrivalRouter)도 TourData 가 채운다.
     */
    private void load(int courseId) {
        api = TourServer.api(this);
        HomeApi requestApi = api;
        TourData.load(this, requestApi, (courses, source) -> {
            if (isFinishing() || isDestroyed() || requestApi != api) return;
            allCourses = courses;
            // 내 코스(#37)는 폰에 저장해 둔 순서대로 장소를 모아 만든다
            setCourse(courseId == MyCourse.COURSE_ID
                    ? MyCourse.build(courses, MyCourse.load(this))
                    : TourData.find(courses, courseId));
        });
    }

    private void setCourse(HomeApi.Course c) {
        if (c == null || c.spots == null || c.spots.isEmpty()) {
            close("코스를 찾을 수 없어요");
            return;
        }
        course = c;
        TourSession.setLastCourse(this, c.id); // 내 투어의 "투어 이어서 하기"가 쓴다
        // 서버 코스는 아직 안 간 첫 장소부터 이어서 걷는다.
        // 내 코스는 방금 고른 코스라 첫 장소부터 보여 준다(이어서 하기로 열었으면 안 간 첫 장소부터). 다른 코스에서 이미 간 곳은 "방문 완료"로 보인다
        boolean resume = getIntent().getBooleanExtra(EXTRA_RESUME, false);
        if (index < 0) index = c.id == MyCourse.COURSE_ID && !resume ? 0 : TourProgress.firstUnvisited(c.spots);
        index = Math.max(0, Math.min(index, c.spots.size() - 1));
        show();
        showPendingCards(); // 다른 화면에 있는 동안 도착한 카드 장소가 있으면 띄운다
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
     * 지도: 내 위치(파란 점) → 지금 가야 할 장소(초록 번호)를 걷는 길을 따라 잇고, 선 가운데에 진행 방향 화살표, 위에 남은 거리.
     * 내 위치를 모르면(권한 거절, 실내 등) 이전 장소(회색 번호)에서 선을 긋는다. 첫 장소면 가야 할 장소만.
     * 선은 걷는 길 경로(TourRoute)를 받은 뒤에 그린다. 받는 중에는 선 없이 번호만 보여 주고(직선이 보였다가 바뀌지 않게),
     * 경로를 받지 못하면 직선으로 그린다. 가야 할 장소 좌표를 모르면 건물 그림.
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
        if (from != null && from.distanceToAsDouble(target) < 1) from = null;  // 같은 자리면 선을 긋지 않는다

        // 선을 그릴 점들: 이 장소로 가는 경로가 있으면 길을 따라(움직여서 새로 받는 동안엔 받아 둔 경로),
        // 경로를 받지 못했으면 직선, 받는 중이면 선을 그리지 않는다
        List<GeoPoint> path = new ArrayList<>();
        boolean onRoad = false, drawLine = false;
        if (from != null) {
            requestRouteIfNeeded(from, target);
            path.add(from);
            if (route != null && sameRouteTarget(target)) {
                // 움직여서 새 경로를 받는 동안엔 받아 둔 경로를 쓰되, 지나온 부분은 빼고 내 위치에서 가장 가까운 점부터 잇는다
                int start = 0;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < route.points.size(); i++) {
                    double[] p = route.points.get(i);
                    double d = from.distanceToAsDouble(new GeoPoint(p[0], p[1]));
                    if (d < best) { best = d; start = i; }
                }
                for (int i = start; i < route.points.size(); i++) {
                    double[] p = route.points.get(i);
                    path.add(new GeoPoint(p[0], p[1]));
                }
                onRoad = true;
            }
            drawLine = onRoad || routeFailed(target);
            path.add(target);
        }

        if (fromMe && (onRoad || drawLine || from == null)) {
            tvDistance.setText(onRoad
                    ? SpotDisplay.routeDistance(route.distanceM, route.durationSec)
                    : SpotDisplay.distance(myLocation.distanceToAsDouble(target)));
            tvDistance.setVisibility(View.VISIBLE);
        } else {
            tvDistance.setVisibility(View.GONE);
        }

        if (from != null && !fromMe) mapView.getOverlays().add(spotMarker(from, String.valueOf(index), 0xFF9AA0A6));
        if (drawLine) {
            // 흰 테두리 + 초록 선 (지도 위에서 잘 보이게 두 번 그린다)
            mapView.getOverlays().add(line(path, 0xFFFFFFFF, 9 * density));
            mapView.getOverlays().add(line(path, ContextCompat.getColor(this, R.color.tour_bar), 5 * density));

            // 선 길이의 가운데쯤에 진행 방향 화살표
            int mid = Math.max(0, Math.min(path.size() - 2, path.size() / 2 - 1));
            GeoPoint a = path.get(mid), b = path.get(mid + 1);
            Marker arrow = new Marker(mapView);
            arrow.setPosition(new GeoPoint((a.getLatitude() + b.getLatitude()) / 2,
                    (a.getLongitude() + b.getLongitude()) / 2));
            arrow.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            arrow.setIcon(TourMap.arrowIcon(this, a.bearingTo(b), ContextCompat.getColor(this, R.color.tour_bar)));
            arrow.setInfoWindow(null);
            mapView.getOverlays().add(arrow);
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
                BoundingBox box = BoundingBox.fromGeoPoints(path);
                int padding = Math.round(44 * density);
                if (mapView.getWidth() > 0) mapView.zoomToBoundingBox(box, false, padding);
                else mapView.addOnFirstLayoutListener((v, l, t, r, bb) -> mapView.zoomToBoundingBox(box, false, padding));
            }
        }
        mapView.invalidate();
    }

    private void retryRoute() {
        if (course != null && !isFinishing() && !isDestroyed()) showMap();
    }

    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return a != null && b != null && a.distanceToAsDouble(b) < 1;
    }

    /** 마지막으로 받은(또는 실패한) 경로가 이 장소로 가는 것인지 */
    private boolean sameRouteTarget(GeoPoint target) {
        return samePoint(routeTarget, target);
    }

    /** 이 장소로 가는 경로를 받지 못했고, 지금 다시 받는 중도 아닌지. 이때만 직선을 긋는다 */
    private boolean routeFailed(GeoPoint target) {
        return route == null && sameRouteTarget(target) && routeCall == null;
    }

    /**
     * 경로가 없거나 많이 움직였을 때만 새로 받는다. 받으면 지도를 다시 그린다.
     * 다른 장소로 넘어가면 바로 받는다. 같은 장소는 서비스 사용 조건 때문에 5초 안에 다시 요청하지 않고,
     * 실패하면 15초 뒤에 다시 시도한다. 미룬 요청은 그 시간이 지나면 알아서 다시 시도한다.
     */
    private void requestRouteIfNeeded(GeoPoint from, GeoPoint target) {
        if (routeCall != null) {
            if (samePoint(routeCallTarget, target)) return;  // 이 장소 경로를 이미 받는 중
            routeCall.cancel();                              // 다른 장소로 넘어갔으면 예전 요청은 버린다
            routeCall = null;
        }
        mapView.removeCallbacks(routeRetry);
        long now = System.currentTimeMillis();
        if (sameRouteTarget(target)) {
            boolean near = routeFrom != null && routeFrom.distanceToAsDouble(from) < ROUTE_REFRESH_M;
            if (route != null && near) return;  // 이미 쓸 만한 경로가 있음
            long wait = (route == null ? ROUTE_RETRY_MS : ROUTE_MIN_INTERVAL_MS) - (now - lastRouteRequestMs);
            if (wait > 0) {
                mapView.postDelayed(routeRetry, wait);
                return;
            }
        }
        lastRouteRequestMs = now;

        GeoPoint reqFrom = from, reqTarget = target;
        okhttp3.Call[] self = new okhttp3.Call[1];
        self[0] = TourRoute.request(from.getLatitude(), from.getLongitude(),
                target.getLatitude(), target.getLongitude(), result -> {
                    if (routeCall != self[0]) return;  // 버린 요청
                    routeCall = null;
                    if (isFinishing() || isDestroyed() || course == null) return;
                    route = result;
                    routeFrom = reqFrom;
                    routeTarget = reqTarget;
                    showMap();
                });
        routeCall = self[0];
        routeCallTarget = target;
    }

    private Polyline line(List<GeoPoint> points, int color, float width) {
        Polyline line = new Polyline(mapView);
        line.setPoints(points);
        Paint paint = line.getOutlinePaint();
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
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
            // 지점별 시간은 이동 + 관람 합계라 "약 N분"으로 보여 준다 (#52). 내 코스는 시간이 없어서(0) 숨긴다
            tvNextWalk.setText(SpotDisplay.walk(next.minutes));
            tvNextWalk.setVisibility(next.minutes > 0 ? View.VISIBLE : View.GONE);
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
        showPendingCards();
    }

    /**
     * 장소 카드 (#37 RF-FX-03). 도착이 기록됐는데 아직 띄우지 않은 카드 장소가 있으면 한 장씩 띄운다.
     * 지금 안내 중인 장소가 아니어도 띄운다(자동 도착으로 지나간 장소). 장소마다 한 번만.
     */
    private void showPendingCards() {
        if (cardShowing || allCourses == null || isFinishing() || isDestroyed()) return;
        List<Integer> pending = TourCards.pendingNow(allCourses);
        if (pending.isEmpty()) return;
        int spotId = pending.get(0);
        TourCards.markSeen(spotId);
        TourCards.Card card = TourCards.cardFor(spotId, allCourses, TourCards.visitedIds());
        if (card == null) {
            showPendingCards();
            return;
        }
        cardShowing = true;
        TourCardSheet.show(this, card, () -> {
            cardShowing = false;
            showPendingCards();
        });
    }

    /**
     * "센서 N 도착"을 도착으로 기록한다. (#29, #37)
     * 지금 안내 중인 장소가 아니어도, 그 센서가 설치된 모든 장소를 기록한다. (TourScanner.recordSensorArrival)
     * 실제 자동 도착은 TourScanner가 직접 기록하고 scanListener로 알려 주므로, 이 메서드는 직접 부를 때(시험 등)만 쓴다.
     */
    public void onSensorArrived(int sensorId) {
        TourScanner.get(this).recordSensorArrival(sensorId);
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

    /** 마지막 장소의 "투어 마치기": 내 투어 화면에서 방문 기록과 걸린 시간을 본다 (#37) */
    private void finishTour() {
        MyTourActivity.open(this);
        finish();
    }
}
