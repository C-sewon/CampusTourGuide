package com.example.myapplication;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

/**
 * 앱 디자인(흰 카드, 진한 초록 버튼)에 맞춘 확인 팝업. 레이아웃은 dialog_tour_confirm.xml.
 * 안드로이드 기본 팝업 대신 쓴다. 버튼은 세로로 크게 놓아 누르기 쉽게 한다.
 * - 주 버튼(채움): 하려는 동작
 * - 보조 버튼(테두리): 다른 선택지. 필요 없으면 null
 * - 취소(글자): 아무것도 하지 않고 닫는다
 */
public final class TourDialog {

    private TourDialog() {}

    public static void show(Context context, String title, String message,
                            String primary, Runnable onPrimary,
                            String secondary, Runnable onSecondary,
                            String cancel) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_tour_confirm, null, false);
        ((TextView) view.findViewById(R.id.tvDialogTitle)).setText(title);
        ((TextView) view.findViewById(R.id.tvDialogMessage)).setText(message);

        Dialog dialog = new AlertDialog.Builder(context).setView(view).create();

        MaterialButton btnPrimary = view.findViewById(R.id.btnDialogPrimary);
        btnPrimary.setText(primary);
        btnPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPrimary != null) onPrimary.run();
        });

        MaterialButton btnSecondary = view.findViewById(R.id.btnDialogSecondary);
        if (secondary == null) {
            btnSecondary.setVisibility(View.GONE);
        } else {
            btnSecondary.setText(secondary);
            btnSecondary.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSecondary != null) onSecondary.run();
            });
        }

        TextView btnCancel = view.findViewById(R.id.btnDialogCancel);
        btnCancel.setText(cancel);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        // 기본 팝업의 흰 배경과 여백을 없애고 카드 모양(bg_tour_card)만 보이게 한다
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int margin = Math.round(24 * context.getResources().getDisplayMetrics().density);
            int width = context.getResources().getDisplayMetrics().widthPixels - margin * 2;
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
}
