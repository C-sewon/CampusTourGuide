package com.example.myapplication;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

/** 여러 화면이 같이 쓰는 색 규칙 */
public final class TourStyle {

    private TourStyle() {}

    /** 추천 이유 줄 색. 덥거나 추우면 주황, 쾌적하면 초록, 모르면 회색 */
    public static void applyTone(Context context, View row, TextView text, ImageView icon,
                                 HomeDisplay.Tone tone) {
        int bg, fg;
        switch (tone) {
            case WARM:
                bg = R.color.tour_warm_bg;
                fg = R.color.tour_warm_text;
                break;
            case GOOD:
                bg = R.color.tour_mint;
                fg = R.color.tour_primary;
                break;
            default:
                bg = R.color.tour_grey_bg;
                fg = R.color.tour_text_secondary;
        }
        row.setBackgroundTintList(ContextCompat.getColorStateList(context, bg));
        text.setTextColor(ContextCompat.getColor(context, fg));
        icon.setColorFilter(ContextCompat.getColor(context, fg));
    }

    /** 코스 장소 순서의 방문 칩. 방문은 민트 + 체크, 미방문은 회색 */
    public static void applyVisitChip(Context context, TextView chip, boolean visited,
                                      String visitedText, String notVisitedText) {
        chip.setText(visited ? visitedText : notVisitedText);
        chip.setBackgroundResource(R.drawable.bg_tour_chip);
        chip.setBackgroundTintList(ContextCompat.getColorStateList(context,
                visited ? R.color.tour_mint : R.color.tour_grey_bg));
        int fg = ContextCompat.getColor(context, visited ? R.color.tour_primary : R.color.tour_text_secondary);
        chip.setTextColor(fg);
        setStartIcon(chip, visited ? R.drawable.ic_tour_tick : 0, fg, 13);
    }

    /** 글자 앞 작은 아이콘. iconRes 가 0 이면 지운다 */
    public static void setStartIcon(TextView view, int iconRes, int color, int sizeDp) {
        if (iconRes == 0) {
            view.setCompoundDrawables(null, null, null, null);
            return;
        }
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(view.getContext(), iconRes).mutate();
        int px = Math.round(sizeDp * view.getResources().getDisplayMetrics().density);
        d.setBounds(0, 0, px, px);
        d.setTint(color);
        view.setCompoundDrawablesRelative(d, null, null, null);
        view.setCompoundDrawablePadding(Math.round(4 * view.getResources().getDisplayMetrics().density));
    }

    /** 진한 초록 채운 버튼 (프로토타입 .btn) */
    public static void styleFilled(Context context, MaterialButton button) {
        button.setEnabled(true);
        button.setStrokeWidth(0);
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.tour_primary));
        button.setTextColor(ContextCompat.getColor(context, R.color.tour_on_primary));
        button.setIconTint(ContextCompat.getColorStateList(context, R.color.tour_on_primary));
    }

    /** 테두리 버튼 (프로토타입 .btn-o). 누를 수 없으면 연한 회색 */
    public static void styleOutlined(Context context, MaterialButton button, boolean enabled) {
        int color = enabled ? R.color.tour_primary : R.color.tour_disabled;
        int text = enabled ? R.color.tour_primary : R.color.tour_disabled_text;
        button.setEnabled(enabled);
        button.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
        button.setStrokeColor(ContextCompat.getColorStateList(context, color));
        button.setStrokeWidth(Math.round(1.5f * context.getResources().getDisplayMetrics().density));
        button.setTextColor(ContextCompat.getColor(context, text));
        button.setIconTint(ContextCompat.getColorStateList(context, text));
    }

    /** 센서 온도 색. 서버 추천 기준(27℃ / 5℃)과 같다. 값이 없으면 회색 */
    public static int sensorColor(Double celsius) {
        switch (HomeDisplay.sensorTone(celsius)) {
            case HOT: return 0xFFE53935;
            case COLD: return 0xFF2F6BD9;
            case COMFORTABLE: return 0xFF16A06A;
            default: return 0xFF9AA0A6;
        }
    }
}
