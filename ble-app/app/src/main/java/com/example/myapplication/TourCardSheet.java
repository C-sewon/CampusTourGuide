package com.example.myapplication;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

/**
 * 장소 카드를 화면 아래에서 올려 보여 준다 (#37 RF-FX-03). 레이아웃은 sheet_tour_card.xml.
 * 어떤 카드를 띄울지는 TourCards 가 정한다. 닫히면 onDismiss 를 부른다 (다음 카드가 있으면 이어서 띄우려고).
 */
public final class TourCardSheet {

    private TourCardSheet() {}

    public static void show(Context context, TourCards.Card card, Runnable onDismiss) {
        View view = LayoutInflater.from(context).inflate(R.layout.sheet_tour_card, null, false);
        TextView kind = view.findViewById(R.id.tvCardKind);
        TextView title = view.findViewById(R.id.tvCardTitle);
        TextView sub = view.findViewById(R.id.tvCardSub);
        TextView intro = view.findViewById(R.id.tvCardIntro);
        ImageView icon = view.findViewById(R.id.ivCardIcon);
        MaterialButton ok = view.findViewById(R.id.btnCardOk);

        ((TextView) view.findViewById(R.id.tvCardCollection)).setText(TourCards.collection(card.collected, card.total));

        switch (card.kind) {
            case COMPLETE:
                kind.setText(R.string.card_kind_complete);
                kind.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.tour_warm_bg));
                kind.setTextColor(ContextCompat.getColor(context, R.color.tour_warm_chip_text));
                icon.setImageResource(R.drawable.ic_tour_tick);
                title.setText(R.string.card_complete_title);
                sub.setText(R.string.card_complete_sub);
                intro.setText(TourCards.completeIntro(card.coreNames));
                ok.setText(R.string.card_ok_complete);
                break;
            case CORE:
                kind.setText(R.string.card_kind_core);
                icon.setImageResource(R.drawable.ic_tour_pin);
                title.setText(card.spot.name);
                sub.setText(R.string.card_sub_new);
                intro.setText(TourCards.firstSentence(card.spot.description));
                break;
            default:
                kind.setText(R.string.card_kind_intro);
                icon.setImageResource(R.drawable.ic_tour_book);
                title.setText(card.spot.name);
                sub.setText(R.string.card_sub_new);
                intro.setText(TourCards.firstSentence(card.spot.description));
        }
        intro.setVisibility(intro.getText().length() == 0 ? View.GONE : View.VISIBLE);
        showCore(context, view, card);

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(view);
        dialog.setOnDismissListener(d -> {
            if (onDismiss != null) onDismiss.run();
        });
        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        // 기본 시트의 흰 사각 배경 대신 위가 둥근 카드 배경(bg_tour_sheet)만 보이게 하고, 처음부터 끝까지 펼친다
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /** 핵심 코스 진행: 장소마다 점(간 곳은 진한 초록) + "핵심 코스 3곳 중 M곳 방문". 핵심 코스 카드가 아니면 숨긴다 */
    private static void showCore(Context context, View view, TourCards.Card card) {
        View row = view.findViewById(R.id.rowCore);
        if (card.coreNames.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout dots = view.findViewById(R.id.coreDots);
        for (Boolean visited : card.coreVisited) {
            View dot = new View(context);
            dot.setBackgroundResource(Boolean.TRUE.equals(visited) ? R.drawable.bg_tour_oval : R.drawable.bg_tour_oval_outline);
            if (Boolean.TRUE.equals(visited)) {
                dot.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.tour_bar));
            }
            int size = Math.round(12 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(Math.round(5 * density));
            dots.addView(dot, lp);
        }
        ((TextView) view.findViewById(R.id.tvCoreProgress))
                .setText(TourCards.coreProgress(card.coreVisitedCount(), card.coreNames.size()));
    }
}
