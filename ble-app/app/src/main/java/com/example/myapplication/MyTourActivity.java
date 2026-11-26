package com.example.myapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 내 투어 (프로토타입 "내 투어", #37 RF-FX-02). 하단 '내 투어' 탭에서 연다.
 * 방문 기록(TourProgress)을 도착한 순서대로 보여 준다: 장소별 도착 시각, 앞 장소에서 걸린 시간, 총 경과 시간.
 * 기록은 폰 안에만 있고 서버로 보내지 않는다. 서버가 없어도 장소 이름은 저장본이나 기본 목록(TourData)에서 찾는다.
 */
public class MyTourActivity extends AppCompatActivity {

    private TextView tvHeadline, tvHeadlineSub, tvElapsed, tvCount, tvVisitsTitle;
    private View cardStats, cardVisits;
    private LinearLayout visitList;
    private final Map<Integer, String> spotNames = new HashMap<>();

    /** 투어 화면이 켜져 있는 동안 자동 도착이 기록되면 바로 다시 그린다 */
    private final TourScanner.Listener scanListener = new TourScanner.Listener() {
        @Override
        public void onSpotArrived(int spotId, int sensorId) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TourProgress.init(this);
        setContentView(R.layout.activity_my_tour);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));

        tvHeadline = findViewById(R.id.tvHeadline);
        tvHeadlineSub = findViewById(R.id.tvHeadlineSub);
        tvElapsed = findViewById(R.id.tvElapsed);
        tvCount = findViewById(R.id.tvCount);
        tvVisitsTitle = findViewById(R.id.tvVisitsTitle);
        cardStats = findViewById(R.id.cardStats);
        cardVisits = findViewById(R.id.cardVisits);
        visitList = findViewById(R.id.visitList);

        TourTabs.setup(this, TourTabs.Tab.MY_TOUR);
        findViewById(R.id.btnCourses).setOnClickListener(v -> CourseActivity.open(this, null));
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());

        // 장소 이름: 서버 없이도 찾을 수 있게 저장본이나 기본 목록에서 먼저 채우고, 서버 목록을 받으면 다시 채운다
        fillNames(TourData.offline(this).courses);
        TourData.load(this, TourServer.api(this), (courses, source) -> {
            if (isFinishing() || isDestroyed()) return;
            fillNames(courses);
            render();
        });
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
        render();
    }

    private void fillNames(List<HomeApi.Course> courses) {
        for (HomeApi.Spot s : MyCourse.allSpots(courses)) spotNames.put(s.id, s.name);
    }

    private void render() {
        List<TourProgress.Visit> visits = MyTourDisplay.sorted(TourProgress.visits());
        int count = visits.size();
        tvHeadline.setText(MyTourDisplay.headline(count));
        tvHeadlineSub.setText(MyTourDisplay.headlineSub(count));

        boolean empty = count == 0;
        cardStats.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvVisitsTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        cardVisits.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        tvElapsed.setText(count < 2 ? "-" : MyTourDisplay.duration(MyTourDisplay.elapsed(visits)));
        tvCount.setText(count + "곳");

        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;
        TimeZone zone = TimeZone.getDefault();
        visitList.removeAllViews();
        Long previous = null;
        for (int i = 0; i < visits.size(); i++) {
            TourProgress.Visit v = visits.get(i);
            View row = inflater.inflate(R.layout.item_my_tour_visit, visitList, false);
            String name = spotNames.get(v.spotId);
            ((TextView) row.findViewById(R.id.tvVisitName)).setText(name != null ? name : "장소 " + v.spotId);
            ((TextView) row.findViewById(R.id.tvVisitTime)).setText(MyTourDisplay.clock(v.arrivedAt, zone));
            ((TextView) row.findViewById(R.id.tvVisitGap)).setText(MyTourDisplay.gap(previous, v.arrivedAt));
            TextView manual = row.findViewById(R.id.tvVisitManual);
            manual.setVisibility(v.manual ? View.VISIBLE : View.GONE);
            manual.setText(MyTourDisplay.manualChip());
            visitList.addView(row);
            previous = v.arrivedAt;

            if (i < visits.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.tour_row_divider));
                visitList.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
            }
        }
    }

    /**
     * 기록 지우기는 되돌릴 수 없어서 한 번 더 묻는다. (앱 디자인 팝업 TourDialog)
     * 모든 장소의 방문 기록을 지워서, 같은 길을 다시 걸으면 진행률과 카드가 처음부터 다시 나온다. (PR #57 리뷰)
     */
    private void confirmClear() {
        if (TourProgress.visits().isEmpty()) return;
        TourDialog.show(this,
                getString(R.string.my_tour_clear_title),
                getString(R.string.my_tour_clear_message),
                getString(R.string.my_tour_clear_ok), () -> {
                    TourProgress.clear();
                    render();
                },
                null, null,
                getString(R.string.my_tour_clear_cancel));
    }
}
