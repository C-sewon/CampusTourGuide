package com.example.myapplication;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 걷는 길을 따라가는 경로를 받아온다.
 * OpenStreetMap 걷기 경로 서비스(routing.openstreetmap.de, FOSSGIS 운영, OSRM)를 쓴다. 무료, 키 불필요.
 * 적게 쓰는 용도로만 써야 하므로 부르는 쪽에서 요청 간격을 제한한다 (SpotActivity).
 */
public final class TourRoute {

    private static final String BASE = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private TourRoute() {}

    /** 받아온 경로. points 는 {위도, 경도} 목록 */
    public static final class Route {
        public final List<double[]> points;
        public final double distanceM;
        public final double durationSec;

        Route(List<double[]> points, double distanceM, double durationSec) {
            this.points = Collections.unmodifiableList(points);
            this.distanceM = distanceM;
            this.durationSec = durationSec;
        }
    }

    public interface Listener {
        /** 실패하면 route 가 null (화면은 직선으로 그린다). 화면 스레드에서 불린다 */
        void onRoute(Route route);
    }

    /**
     * 요청 주소. 서비스는 경도,위도 순서를 받는다.
     * 출발점(내 위치)은 소수점 4자리(약 10m)로 줄여 보내서 정확한 위치를 덜 알린다. 도착점은 장소 좌표라 그대로 보낸다.
     */
    static String url(double fromLat, double fromLon, double toLat, double toLon) {
        return String.format(Locale.ROOT, "%s%.4f,%.4f;%.6f,%.6f?overview=full&geometries=geojson",
                BASE, fromLon, fromLat, toLon, toLat);
    }

    /** 응답 JSON 에서 경로를 꺼낸다. 형식이 다르거나 경로가 없으면 null */
    static Route parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("code") || !"Ok".equals(root.get("code").getAsString())) return null;
            JsonArray routes = root.getAsJsonArray("routes");
            if (routes == null || routes.size() == 0) return null;
            JsonObject route = routes.get(0).getAsJsonObject();
            JsonArray coords = route.getAsJsonObject("geometry").getAsJsonArray("coordinates");
            List<double[]> points = new ArrayList<>();
            for (JsonElement c : coords) {
                JsonArray lonLat = c.getAsJsonArray();
                points.add(new double[]{lonLat.get(1).getAsDouble(), lonLat.get(0).getAsDouble()});
            }
            if (points.size() < 2) return null;
            return new Route(points, route.get("distance").getAsDouble(), route.get("duration").getAsDouble());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 경로를 요청한다. 돌려준 Call 로 취소할 수 있다 */
    public static Call request(double fromLat, double fromLon, double toLat, double toLon, Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        Request request = new Request.Builder()
                .url(url(fromLat, fromLon, toLat, toLon))
                .header("User-Agent", "CampusOn/0.1 (Android)")
                .build();
        Call call = CLIENT.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                if (!c.isCanceled()) main.post(() -> listener.onRoute(null));
            }

            @Override
            public void onResponse(Call c, Response response) {
                Route route = null;
                try {
                    if (response.isSuccessful() && response.body() != null) route = parse(response.body().string());
                } catch (IOException ignored) {
                    // 실패면 직선으로 그린다
                } finally {
                    response.close();
                }
                Route result = route;
                if (!c.isCanceled()) main.post(() -> listener.onRoute(result));
            }
        });
        return call;
    }
}
