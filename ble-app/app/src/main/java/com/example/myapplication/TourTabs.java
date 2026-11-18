package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

/** 하단 탭(탐색 / 내 투어 / 가이드). 레이아웃은 view_tour_tabs.xml */
public final class TourTabs {

    public enum Tab { EXPLORE, MY_TOUR, GUIDE }

    private TourTabs() {}

    /** 현재 탭을 강조하고, 다른 탭을 누르면 그 화면으로 간다 */
    public static void setup(Activity activity, Tab current) {
        bind(activity, R.id.tabExplore, Tab.EXPLORE, current);
        bind(activity, R.id.tabMyTour, Tab.MY_TOUR, current);
        bind(activity, R.id.tabGuide, Tab.GUIDE, current);
    }

    private static void bind(Activity activity, int viewId, Tab tab, Tab current) {
        TextView view = activity.findViewById(viewId);
        boolean selected = tab == current;
        int color = ContextCompat.getColor(activity,
                selected ? R.color.tour_primary : R.color.tour_text_secondary);
        view.setTextColor(color);
        view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color));
        view.setSelected(selected);
        if (selected) {
            view.setOnClickListener(null);
            return;
        }
        view.setOnClickListener(v -> open(activity, tab));
    }

    private static void open(Activity from, Tab tab) {
        switch (tab) {
            case EXPLORE:
                Intent intent = new Intent(from, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                from.startActivity(intent);
                from.overridePendingTransition(0, 0);
                break;
            case MY_TOUR:
                Toast.makeText(from, "내 투어 화면은 준비 중이에요", Toast.LENGTH_SHORT).show();
                break;
            default:
                Toast.makeText(from, "가이드는 준비 중이에요", Toast.LENGTH_SHORT).show();
        }
    }
}
