package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 코스 목록(GET /api/courses)을 가져오는 곳. (#37)
 * 서버나 인터넷이 안 돼도 투어와 방문 기록이 되도록 이 순서로 쓴다.
 * 1. 서버에서 받기. 받으면 폰에 저장해 둔다.
 * 2. 서버가 안 되면 폰에 저장해 둔 마지막 목록
 * 3. 저장해 둔 것도 없으면 앱에 넣어 둔 기본 목록 (assets/tour_default.json)
 *
 * 기본 목록은 서버가 응답에서 그대로 뽑은 파일이다. (서버 저장소 docs/tour_default.json, python -m app.export_tour)
 * 장소나 코스가 바뀌면 그 파일을 다시 복사해 넣는다. 온도는 비어 있어서 "값을 받을 수 없어요"로 보인다.
 * 코스 목록은 공개된 장소 정보라 폰에 저장해도 개인정보 문제가 없다.
 */
public final class TourData {

    /** 목록이 어디서 왔는지 */
    public enum Source { SERVER, SAVED, DEFAULT }

    public interface Listener {
        void onCourses(List<HomeApi.Course> courses, Source source);
    }

    static final String DEFAULT_ASSET = "tour_default.json";
    private static final String PREFS = "tour_data";
    private static final String PREF_COURSES = "courses_json";
    private static final Gson GSON = new Gson();
    private static final Type COURSE_LIST = new TypeToken<List<HomeApi.Course>>() {}.getType();

    private TourData() {}

    /**
     * 서버에서 코스 목록을 받는다. api 가 null(서버 주소 없음)이거나 실패하면 저장본, 그것도 없으면 기본 목록을 준다.
     * 어느 목록이든 받으면 자동 도착용 센서-장소 연결(ArrivalRouter)도 새로 채운다.
     */
    public static void load(Context context, HomeApi api, Listener listener) {
        Context app = context.getApplicationContext();
        if (api == null) {
            deliver(offline(app), listener);
            return;
        }
        api.courses().enqueue(new Callback<List<HomeApi.Course>>() {
            @Override
            public void onResponse(Call<List<HomeApi.Course>> call, Response<List<HomeApi.Course>> res) {
                List<HomeApi.Course> body = res.body();
                if (res.isSuccessful() && body != null && !body.isEmpty()) {
                    save(app, body);
                    deliver(new Loaded(body, Source.SERVER), listener);
                } else {
                    deliver(offline(app), listener);
                }
            }

            @Override
            public void onFailure(Call<List<HomeApi.Course>> call, Throwable t) {
                deliver(offline(app), listener);
            }
        });
    }

    /** 서버 없이 쓸 목록: 저장본, 없으면 기본 목록. 앱을 켤 때 자동 도착 연결을 바로 채우는 데도 쓴다 */
    public static Loaded offline(Context context) {
        String saved = prefs(context).getString(PREF_COURSES, null);
        List<HomeApi.Course> courses = parse(saved);
        if (courses != null) return new Loaded(courses, Source.SAVED);
        courses = parse(readAsset(context, DEFAULT_ASSET));
        return new Loaded(courses != null ? courses : new ArrayList<>(), Source.DEFAULT);
    }

    /** 앱을 켤 때 부른다. 서버를 기다리지 않고 저장본이나 기본 목록으로 자동 도착 연결을 채운다 (#37, 황지유 요청) */
    public static void prepareArrivals(Context context) {
        ArrivalRouter.update(offline(context.getApplicationContext()).courses);
    }

    /** JSON 을 코스 목록으로. 형식이 틀리거나 비어 있으면 null */
    static List<HomeApi.Course> parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            List<HomeApi.Course> courses = GSON.fromJson(json, COURSE_LIST);
            return courses == null || courses.isEmpty() ? null : courses;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** 서버 없이 보여줄 추천. 기온을 모르므로 서버 규칙(모르면 가장 긴 코스, 같으면 번호가 작은 코스)을 따른다 */
    public static HomeApi.Recommendation offlineRecommendation(List<HomeApi.Course> courses) {
        HomeApi.Recommendation rec = new HomeApi.Recommendation();
        rec.condition = "unknown";
        rec.temperature = null;
        rec.courses = new ArrayList<>();
        HomeApi.Course best = longest(courses);
        if (best != null) {
            rec.recommendedCourseId = best.id;
            rec.recommendedCourseName = best.name;
        }
        if (courses != null) {
            for (HomeApi.Course c : courses) {
                HomeApi.CourseChoice choice = new HomeApi.CourseChoice();
                choice.id = c.id;
                choice.totalMinutes = c.totalMinutes;
                choice.recommended = best != null && c.id == best.id;
                rec.courses.add(choice);
            }
        }
        return rec;
    }

    static HomeApi.Course longest(List<HomeApi.Course> courses) {
        HomeApi.Course best = null;
        if (courses == null) return null;
        for (HomeApi.Course c : courses) {
            if (best == null || c.totalMinutes > best.totalMinutes
                    || (c.totalMinutes == best.totalMinutes && c.id < best.id)) {
                best = c;
            }
        }
        return best;
    }

    public static HomeApi.Course find(List<HomeApi.Course> courses, int courseId) {
        if (courses == null) return null;
        for (HomeApi.Course c : courses) if (c.id == courseId) return c;
        return null;
    }

    /** 받은 목록과 출처 */
    public static final class Loaded {
        public final List<HomeApi.Course> courses;
        public final Source source;

        Loaded(List<HomeApi.Course> courses, Source source) {
            this.courses = courses;
            this.source = source;
        }
    }

    private static void deliver(Loaded loaded, Listener listener) {
        ArrivalRouter.update(loaded.courses); // 센서 N 도착 → 장소 (#29)
        listener.onCourses(loaded.courses, loaded.source);
    }

    private static void save(Context context, List<HomeApi.Course> courses) {
        prefs(context).edit().putString(PREF_COURSES, withoutReadings(courses)).apply();
    }

    /**
     * 저장할 JSON. 온도는 지운다. 나중에 서버 없이 열었을 때 예전 온도가 지금 값처럼 보이면 안 되기 때문이다.
     * (기본 목록과 같은 규칙. 화면에서 쓰는 원본 목록은 건드리지 않도록 복사본에서 지운다)
     */
    static String withoutReadings(List<HomeApi.Course> courses) {
        List<HomeApi.Course> copy = GSON.fromJson(GSON.toJson(courses, COURSE_LIST), COURSE_LIST);
        for (HomeApi.Course c : copy) {
            if (c.spots == null) continue;
            for (HomeApi.CourseSpot cs : c.spots) {
                if (cs.spot == null) continue;
                cs.spot.latestReading = null;
                cs.spot.valueSource = null;
            }
        }
        return GSON.toJson(copy, COURSE_LIST);
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
