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

    /** 장소 안내의 "투어 마치기"에서 연다. 하단 탭으로 열 때와 같이 이미 열려 있으면 그 화면을 앞으로 */
    public static void open(Context context) {
        context.startActivity(new Intent(context, MyTourActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private TextView tvHeadline, tvHeadlineSub, tvElapsed, tvCount, tvCards, tvVisitsTitle, tvCoursesLink;
    private View cardStats, cardVisits;
    private LinearLayout visitList;
    private MaterialButton btnResume, btnCourses;
    private final Map<Integer, String> spotNames = new HashMap<>();
    /** 장소 이름, 카드 장소, 걷던 코스를 찾을 때 쓰는 코스 목록 */
    private List<HomeApi.Course> courses;

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
        tvCards = findViewById(R.id.tvCards);
        tvCoursesLink = findViewById(R.id.tvCoursesLink);
        btnResume = findViewById(R.id.btnResume);
        btnCourses = findViewById(R.id.btnCourses);
        tvVisitsTitle = findViewById(R.id.tvVisitsTitle);
        cardStats = findViewById(R.id.cardStats);
        cardVisits = findViewById(R.id.cardVisits);
        visitList = findViewById(R.id.visitList);

        TourTabs.setup(this, TourTabs.Tab.MY_TOUR);
        btnCourses.setOnClickListener(v -> CourseActivity.open(this, null));
        tvCoursesLink.setOnClickListener(v -> CourseActivity.open(this, null));
        btnResume.setOnClickListener(v -> {
            Integer id = TourSession.lastCourse(this);
            if (id != null) SpotActivity.resume(this, id);
        });
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

    private void fillNames(List<HomeApi.Course> list) {
        courses = list;
        for (HomeApi.Spot s : MyCourse.allSpots(list)) spotNames.put(s.id, s.name);
    }

    /** 마지막으로 걷던 코스 (장소 안내를 열 때 저장). 없거나 찾을 수 없으면 null */
    private HomeApi.Course lastCourse() {
        Integer id = TourSession.lastCourse(this);
        if (id == null || courses == null) return null;
        return id == MyCourse.COURSE_ID ? MyCourse.build(courses, MyCourse.load(this)) : TourData.find(courses, id);
    }

    /** 걷던 코스가 남았으면 "이어서 걷기"를 주 버튼으로, 아니면 "다른 코스 둘러보기"를 주 버튼으로 */
    private void renderCourse() {
        HomeApi.Course c = lastCourse();
        int total = c != null && c.spots != null ? c.spots.size() : 0;
        int visited = c != null ? TourProgress.countVisited(c.spots) : 0;
        String progress = c != null ? MyTourDisplay.courseProgress(c.name, visited, total) : null;
        if (progress != null) tvHeadlineSub.setText(progress);

        // 내 코스는 3곳 이상일 때만 이어서 걷는다. 걷다가 2곳으로 고친 코스로 3곳 규칙을 지나가지 않게 (PR #65 리뷰)
        boolean tooShort = c != null && c.id == MyCourse.COURSE_ID && total < MyCourse.MIN_SPOTS;
        boolean resume = c != null && !tooShort && MyTourDisplay.canResume(visited, total);
        btnResume.setVisibility(resume ? View.VISIBLE : View.GONE);
        tvCoursesLink.setVisibility(resume ? View.VISIBLE : View.GONE);
        btnCourses.setVisibility(resume ? View.GONE : View.VISIBLE);
        if (resume) btnResume.setText(MyTourDisplay.resumeLabel(c.name, visited, total));
    }

    private void render() {
        List<TourProgress.Visit> visits = MyTourDisplay.sorted(TourProgress.visits());
        int count = visits.size();
        tvHeadline.setText(MyTourDisplay.headline(count));
        tvHeadlineSub.setText(MyTourDisplay.headlineSub(count));
        renderCourse();

        boolean empty = count == 0;
        cardStats.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvVisitsTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        cardVisits.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        TimeZone zone = TimeZone.getDefault();
        tvElapsed.setText(count < 2 ? "-" : MyTourDisplay.duration(MyTourDisplay.elapsed(visits, zone)));
        tvCount.setText(count + "곳");
        tvCards.setText(MyTourDisplay.cards(TourCards.collected(courses, TourCards.visitedIds()),
                TourCards.cardSpots(courses).size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;
        boolean manyDays = MyTourDisplay.manyDays(visits, zone);
        visitList.removeAllViews();
        Long previous = null;
        for (int i = 0; i < visits.size(); i++) {
            TourProgress.Visit v = visits.get(i);
            // 기록이 여러 날이면 날짜가 바뀌는 곳에 날짜 줄 (PR #63 리뷰 2)
            if (manyDays && (previous == null || !MyTourDisplay.sameDay(previous, v.arrivedAt, zone))) {
                visitList.addView(dateRow(MyTourDisplay.dateLabel(v.arrivedAt, zone), density, previous != null));
            }
            View row = inflater.inflate(R.layout.item_my_tour_visit, visitList, false);
            String name = spotNames.get(v.spotId);
            ((TextView) row.findViewById(R.id.tvVisitName)).setText(name != null ? name : "장소 " + v.spotId);
            ((TextView) row.findViewById(R.id.tvVisitTime)).setText(MyTourDisplay.clock(v.arrivedAt, zone));
            ((TextView) row.findViewById(R.id.tvVisitGap)).setText(MyTourDisplay.gap(previous, v.arrivedAt, zone));
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

    /** 발자국 목록 안의 날짜 줄. 예: "11월 28일 (토)" */
    private TextView dateRow(String text, float density, boolean gapAbove) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.tour_text_secondary));
        t.setPadding(0, Math.round((gapAbove ? 14 : 10) * density), 0, Math.round(2 * density));
        return t;
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
