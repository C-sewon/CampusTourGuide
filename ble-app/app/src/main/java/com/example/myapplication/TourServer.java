package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 화면들이 같이 쓰는 서버 주소 설정 (개발용).
 * 서버는 팀원 노트북에서 켜서 IP 가 자주 바뀌므로 코드에 고정하지 않고 앱에서 입력받아 저장한다.
 */
public final class TourServer {

    private static final String PREFS = "home";
    private static final String PREF_SERVER_URL = "server_url";

    private TourServer() {}

    /** 저장된 서버 주소로 만든 API. 주소가 없거나 형식이 틀리면 null (그때는 샘플을 보여준다) */
    public static HomeApi api(Context context) {
        String baseUrl = HomeDisplay.normalizeBaseUrl(prefs(context).getString(PREF_SERVER_URL, ""));
        if (baseUrl.isEmpty()) return null;
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            return new Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(HomeApi.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 서버 주소 입력 창. 저장하면 onSaved 를 부른다 */
    public static void askUrl(Context context, Runnable onSaved) {
        SharedPreferences prefs = prefs(context);
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("예: 192.168.0.5:8000");
        input.setText(prefs.getString(PREF_SERVER_URL, ""));
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(context)
                .setTitle("서버 주소 (개발용)")
                .setMessage("서버를 켠 컴퓨터의 IPv4 주소와 포트. 비우면 샘플 데이터를 보여줘요.")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    prefs.edit().putString(PREF_SERVER_URL, input.getText().toString().trim()).apply();
                    onSaved.run();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
