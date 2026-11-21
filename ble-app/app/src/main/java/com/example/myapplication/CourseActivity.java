package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 코스 둘러보기 화면 (프로토타입 "코스 둘러보기"). 홈의 추천 코스 카드에서 연다.
 * 코스 카드를 눌러 고르면 아래에 장소 순서가 나오고, 아래 버튼으로 그 코스를 시작한다.
 * 서버 GET /api/courses/recommend 로 추천 코스를, GET /api/courses 로 코스와 장소를 받는다.
 * 서버 주소가 없으면 [샘플] 데이터를 보여준다. 서버 주소는 제목을 길게 눌러 넣는다 (개발용).
 */
public class CourseActivity extends AppCompatActivity {

    private static final String EXTRA_SELECTED = "selected_course_id";

    /** selectedCourseId 를 처음 골라 둔 상태로 연다. null 이면 추천 코스 */
    public static void open(Context context, Integer selectedCourseId) {
        Intent intent = new Intent(context, CourseActivity.class);
        if (selectedCourseId != null) intent.putExtra(EXTRA_SELECTED, (int) selectedCourseId);
        context.startActivity(intent);
    }

    private LinearLayout courseList, stopList;
    private TextView tvMessage, tvStopsTitle;
    private View cardStops;
    private MaterialButton btnStart;

    private HomeApi api;
    private List<HomeApi.Course> courses;
    private HomeApi.Recommendation rec;
    private Integer selectedId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));

        courseList = findViewById(R.id.courseList);
        stopList = findViewById(R.id.stopList);
        tvMessage = findViewById(R.id.tvMessage);
        tvStopsTitle = findViewById(R.id.tvStopsTitle);
        cardStops = findViewById(R.id.cardStops);
        btnStart = findViewById(R.id.btnStart);

        if (getIntent().hasExtra(EXTRA_SELECTED)) selectedId = getIntent().getIntExtra(EXTRA_SELECTED, 0);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.tvCourseTitle).setOnLongClickListener(v -> {
            TourServer.askUrl(this, this::load);
            return true;
        });
        btnStart.setOnClickListener(v -> {
            if (selectedId != null) SpotActivity.start(this, selectedId);
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 장소 안내에서 돌아오면 방문 표시를 새로 그린다
        if (courses != null) render();
    }

    // BLE 자동 도착 (#29, 황지유): 코스를 둘러보는 동안에도 스캔을 이어 간다
    @Override
    protected void onStart() {
        super.onStart();
        TourScanner.get(this).acquire();
    }

    @Override
    protected void onStop() {
        super.onStop();
        TourScanner.get(this).release();
    }

    // ---------- 데이터 불러오기 ----------

    private void load() {
        api = TourServer.api(this);
        if (api == null) {
            show(TourSamples.courses(), TourSamples.recommendation());
            return;
        }
        showMessage("코스를 불러오고 있어요");
        HomeApi requestApi = api;
        api.recommend().enqueue(new Callback<HomeApi.Recommendation>() {
            @Override
            public void onResponse(Call<HomeApi.Recommendation> call, Response<HomeApi.Recommendation> res) {
                if (isGone(requestApi)) return;
                loadCourses(requestApi, res.isSuccessful() ? res.body() : null);
            }

            @Override
            public void onFailure(Call<HomeApi.Recommendation> call, Throwable t) {
                if (isGone(requestApi)) return;
                loadCourses(requestApi, null);  // 추천을 못 받아도 코스 목록은 보여준다
            }
        });
    }

    private void loadCourses(HomeApi requestApi, HomeApi.Recommendation recommendation) {
        requestApi.courses().enqueue(new Callback<List<HomeApi.Course>>() {
            @Override
            public void onResponse(Call<List<HomeApi.Course>> call, Response<List<HomeApi.Course>> res) {
                if (isGone(requestApi)) return;
                if (!res.isSuccessful() || res.body() == null) {
                    showMessage("코스를 받지 못했어요 (" + res.code() + ")");
                    return;
                }
                show(res.body(), recommendation);
            }

            @Override
            public void onFailure(Call<List<HomeApi.Course>> call, Throwable t) {
                if (isGone(requestApi)) return;
                showMessage("서버에 연결할 수 없어요. 제목을 길게 눌러 주소를 확인해 주세요");
            }
        });
    }

    private boolean isGone(HomeApi requestApi) {
        return isFinishing() || isDestroyed() || requestApi != api;
    }

    // ---------- 화면 그리기 ----------

    private void show(List<HomeApi.Course> list, HomeApi.Recommendation recommendation) {
        courses = list;
        rec = recommendation;
        if (courses.isEmpty()) {
            showMessage("아직 등록된 코스가 없어요");
            return;
        }
        if (findCourse(selectedId) == null) {
            selectedId = rec != null && rec.recommendedCourseId != null ? rec.recommendedCourseId : courses.get(0).id;
        }
        render();
    }

    private void showMessage(String message) {
        courses = null;
        courseList.removeAllViews();
        tvMessage.setText(message);
        tvMessage.setVisibility(View.VISIBLE);
        tvStopsTitle.setVisibility(View.GONE);
        cardStops.setVisibility(View.GONE);
        btnStart.setEnabled(false);
    }

    private void render() {
        tvMessage.setVisibility(View.GONE);
        tvStopsTitle.setVisibility(View.VISIBLE);
        cardStops.setVisibility(View.VISIBLE);
        btnStart.setEnabled(true);

        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;

        courseList.removeAllViews();
        for (HomeApi.Course c : courses) {
            MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_course, courseList, false);
            int total = c.spots != null ? c.spots.size() : 0;
            int visited = TourProgress.countVisited(c.spots);
            boolean recommended = rec != null && rec.recommendedCourseId != null && rec.recommendedCourseId == c.id;

            ((TextView) card.findViewById(R.id.tvCourseName)).setText(c.name);
            TextView badge = card.findViewById(R.id.tvBadge);
            badge.setVisibility(recommended ? View.VISIBLE : View.GONE);
            if (recommended) {
                badge.setText(CourseDisplay.badge(rec.condition));
                TourStyle.setStartIcon(badge, R.drawable.ic_tour_sun,
                        ContextCompat.getColor(this, R.color.tour_warm_icon), 13);
            }
            ((TextView) card.findViewById(R.id.tvCourseMeta)).setText(CourseDisplay.courseMeta(c.totalMinutes, total));
            LinearProgressIndicator bar = card.findViewById(R.id.progress);
            bar.setMax(Math.max(total, 1));
            bar.setProgress(visited);
            ((TextView) card.findViewById(R.id.tvProgress)).setText(CourseDisplay.progress(visited, total));

            card.setStrokeWidth(selectedId != null && selectedId == c.id ? Math.round(2 * density) : 0);
            card.setOnClickListener(v -> {
                selectedId = c.id;
                render();
            });
            courseList.addView(card);
        }

        HomeApi.Course selected = findCourse(selectedId);
        tvStopsTitle.setText(CourseDisplay.stopsTitle(selected.name));
        stopList.removeAllViews();
        int number = 1;
        int total = selected.spots != null ? selected.spots.size() : 0;
        for (int i = 0; i < total; i++) {
            HomeApi.Spot spot = selected.spots.get(i).spot;
            boolean visited = spot != null && TourProgress.isVisited(spot.id);
            View row = inflater.inflate(R.layout.item_course_spot, stopList, false);

            TextView num = row.findViewById(R.id.tvSpotNumber);
            num.setText(String.valueOf(number++));
            num.setBackgroundResource(visited ? R.drawable.bg_tour_oval : R.drawable.bg_tour_oval_outline);
            num.setBackgroundTintList(visited ? ContextCompat.getColorStateList(this, R.color.tour_primary) : null);
            num.setTextColor(ContextCompat.getColor(this, visited ? R.color.tour_on_primary : R.color.tour_primary));

            ((TextView) row.findViewById(R.id.tvSpotName)).setText(spot != null ? spot.name : "");
            TextView state = row.findViewById(R.id.tvSpotState);
            TourStyle.applyVisitChip(this, state, visited, "방문", "미방문");
            stopList.addView(row);

            if (i < total - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.tour_row_divider));
                stopList.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
            }
        }
        btnStart.setText(CourseDisplay.startLabel(TourProgress.countVisited(selected.spots), total));
    }

    private HomeApi.Course findCourse(Integer id) {
        if (id == null || courses == null) return null;
        for (HomeApi.Course c : courses) if (c.id == id) return c;
        return null;
    }
}
