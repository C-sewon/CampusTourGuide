package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 마지막으로 걷던 코스 (#37). 내 투어 화면의 "투어 이어서 하기" 버튼이 쓴다.
 * 장소 안내 화면을 열 때 코스 번호를 저장한다. 내 코스는 MyCourse.COURSE_ID(-1).
 * 폰 안에만 저장하고 백업에서 뺀다. (tour_session.xml)
 */
public final class TourSession {

    private static final String PREFS = "tour_session";
    private static final String PREF_COURSE = "last_course";

    private TourSession() {}

    public static void setLastCourse(Context context, int courseId) {
        prefs(context).edit().putInt(PREF_COURSE, courseId).apply();
    }

    /** 마지막으로 걷던 코스 번호. 없으면 null */
    public static Integer lastCourse(Context context) {
        SharedPreferences p = prefs(context);
        return p.contains(PREF_COURSE) ? p.getInt(PREF_COURSE, 0) : null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
