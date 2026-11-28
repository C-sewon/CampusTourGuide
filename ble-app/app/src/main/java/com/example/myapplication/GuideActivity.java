package com.example.myapplication;

import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 가이드 (#64). 하단 '가이드' 탭에서 연다. 앱 사용법(자동 도착, '도착' 버튼, 온도 출처, 카드)과
 * 개인정보 안내(방문 기록은 폰에만, 업로드의 폰 ID, 길 안내의 위치, PR #66)를 보여 준다.
 * 묶음은 처음에 제목만 보이고, 누르면 설명이 펼쳐진다(다시 누르면 접힘).
 * 서버 없이 동작한다. 장소 이름은 폰 저장본이나 기본 목록(TourData)에서 읽는다.
 */
public class GuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.tour_background));
        TourTabs.setup(this, TourTabs.Tab.GUIDE);

        List<HomeApi.Course> courses = TourData.offline(this).courses;
        List<String> core = GuideDisplay.coreSpots(courses);
        List<String> cards = GuideDisplay.cardSpots(courses);

        addSection(R.drawable.ic_tour_route, getString(R.string.guide_steps_title), getString(R.string.guide_steps_body));
        addSection(R.drawable.ic_tour_bt, getString(R.string.guide_auto_title),
                getString(R.string.guide_auto_body, GuideDisplay.join(GuideDisplay.autoSpots(courses))));
        addSection(R.drawable.ic_tour_hand, getString(R.string.guide_manual_title),
                getString(R.string.guide_manual_body, GuideDisplay.manualCount(courses)));
        addSection(R.drawable.ic_tour_therm, getString(R.string.guide_temp_title), getString(R.string.guide_temp_body));
        addSection(R.drawable.ic_tour_book, getString(R.string.guide_cards_title),
                getString(R.string.guide_cards_body, cards.size(), GuideDisplay.join(cards), core.size(), GuideDisplay.join(core)));
        addSection(R.drawable.ic_tour_tick, getString(R.string.guide_privacy_title), getString(R.string.guide_privacy_body));
    }

    // 다른 투어 화면처럼, 가이드를 읽는 동안에도 BLE 자동 도착 스캔을 이어 간다 (탭을 오갈 때 스캔이 끊기지 않게)
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

    private void addSection(int iconRes, String title, String body) {
        LinearLayout list = findViewById(R.id.guideList);
        View section = LayoutInflater.from(this).inflate(R.layout.item_guide_section, list, false);
        ((ImageView) section.findViewById(R.id.ivGuideIcon)).setImageResource(iconRes);
        ((TextView) section.findViewById(R.id.tvGuideTitle)).setText(title);
        TextView tvBody = section.findViewById(R.id.tvGuideBody);
        ImageView chevron = section.findViewById(R.id.ivGuideChevron);
        tvBody.setText(body);
        section.setOnClickListener(v -> {
            boolean open = tvBody.getVisibility() != View.VISIBLE;
            TransitionManager.beginDelayedTransition((ViewGroup) list.getParent(), new AutoTransition().setDuration(180));
            tvBody.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 180 : 0);
        });
        list.addView(section);
    }
}
