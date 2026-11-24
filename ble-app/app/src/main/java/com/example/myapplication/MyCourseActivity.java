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

import java.util.ArrayList;
import java.util.List;

/**
 * 내 코스 만들기 (#37 자유 탐색, RF-FX-01).
 * 장소 12곳 중에서 누르는 순서대로 고르면 번호가 붙고, 시작하면 장소 안내 화면이 그 순서대로 이어진다.
 * 장소 목록은 코스 목록(TourData: 서버, 폰 저장본, 기본 목록)에서 모은다. 서버가 없어도 고를 수 있다.
 * 카드가 뜨는 장소(has_card)에는 "카드" 표시를 붙인다.
 */
public class MyCourseActivity extends AppCompatActivity {

    public static void open(Context context) {
        context.startActivity(new Intent(context, MyCourseActivity.class));
    }

    private LinearLayout spotList;
    private TextView tvSummary, tvMessage;
    private MaterialButton btnStart;

    private List<HomeApi.Spot> spots = new ArrayList<>();
    private MyCourse myCourse = new MyCourse();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TourProgress.init(this);
        setContentView(R.layout.activity_my_course);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));

        spotList = findViewById(R.id.spotList);
        tvSummary = findViewById(R.id.tvSummary);
        tvMessage = findViewById(R.id.tvMessage);
        btnStart = findViewById(R.id.btnStart);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            myCourse = new MyCourse();
            MyCourse.save(this, myCourse.spotIds());
            render();
        });
        btnStart.setOnClickListener(v -> {
            if (!myCourse.canStart()) return;
            MyCourse.save(this, myCourse.spotIds());
            SpotActivity.start(this, MyCourse.COURSE_ID);
        });

        // 지난번에 고른 순서를 이어서 고친다
        myCourse = new MyCourse(MyCourse.load(this));
        render();
        tvMessage.setText("장소를 불러오고 있어요");
        tvMessage.setVisibility(View.VISIBLE);
        TourData.load(this, TourServer.api(this), (courses, source) -> {
            if (isFinishing() || isDestroyed()) return;
            spots = MyCourse.allSpots(courses);
            tvMessage.setVisibility(spots.isEmpty() ? View.VISIBLE : View.GONE);
            if (spots.isEmpty()) tvMessage.setText("장소 목록이 없어요");
            render();
        });
    }

    // BLE 자동 도착 (#29): 다른 투어 화면처럼 이 화면에서도 스캔을 이어 간다
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

    @Override
    protected void onResume() {
        super.onResume();
        // 장소 안내에서 돌아오면 방문 표시를 새로 그린다
        render();
    }

    private void render() {
        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;
        spotList.removeAllViews();
        for (int i = 0; i < spots.size(); i++) {
            HomeApi.Spot spot = spots.get(i);
            View row = inflater.inflate(R.layout.item_course_spot, spotList, false);
            int number = myCourse.numberOf(spot.id);
            boolean selected = number > 0;

            TextView num = row.findViewById(R.id.tvSpotNumber);
            num.setText(selected ? String.valueOf(number) : "");
            num.setBackgroundResource(selected ? R.drawable.bg_tour_oval : R.drawable.bg_tour_oval_outline);
            num.setBackgroundTintList(selected ? ContextCompat.getColorStateList(this, R.color.tour_primary) : null);
            num.setTextColor(ContextCompat.getColor(this, selected ? R.color.tour_on_primary : R.color.tour_primary));

            TextView name = row.findViewById(R.id.tvSpotName);
            name.setText(spot.name);
            if (TourProgress.isVisited(spot.id)) name.append("  ✓");

            TextView chip = row.findViewById(R.id.tvSpotState);
            chip.setVisibility(spot.hasCard ? View.VISIBLE : View.GONE);
            if (spot.hasCard) {
                chip.setText(CourseDisplay.cardChip());
                chip.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tour_warm_bg));
                chip.setTextColor(ContextCompat.getColor(this, R.color.tour_warm_chip_text));
            }

            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> {
                myCourse.toggle(spot.id);
                MyCourse.save(this, myCourse.spotIds());
                render();
            });
            spotList.addView(row);

            if (i < spots.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.tour_row_divider));
                spotList.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
            }
        }
        tvSummary.setText(CourseDisplay.myCourseSummary(myCourse.size(), MyCourse.MIN_SPOTS));
        btnStart.setEnabled(myCourse.canStart());
        btnStart.setAlpha(myCourse.canStart() ? 1f : 0.4f);
    }
}
