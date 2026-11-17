package com.example.myapplication;

import com.google.gson.annotations.SerializedName;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;

/**
 * 홈, 코스 화면이 우리 팀 서버(FastAPI)에서 읽어 오는 API.
 * 응답 모양은 서버 app/schemas.py, app/recommend.py 와 같다. 화면에 쓰는 필드만 받는다.
 */
public interface HomeApi {

    /** 등록된 센서와 좌표 */
    @GET("api/sensors")
    Call<List<Sensor>> sensors();

    /** 센서별 최신 측정값 */
    @GET("api/sensors/status")
    Call<List<SensorStatus>> sensorStatus();

    /** 지금 기온에 맞는 추천 코스 */
    @GET("api/courses/recommend")
    Call<Recommendation> recommend();

    /** 코스 목록과 각 코스의 장소 */
    @GET("api/courses")
    Call<List<Course>> courses();

    class Sensor {
        @SerializedName("sensor_id") int sensorId;
        /** opensrc: 우리 팀 센서, lab: 연구실 센서 */
        String kind;
        String location;
        Double latitude;
        Double longitude;
    }

    class Reading {
        double temperature;
        /** 연구실 센서 값에는 습도가 없어 null */
        Double humidity;
        @SerializedName("age_minutes") int ageMinutes;
        @SerializedName("is_stale") boolean isStale;
        @SerializedName("is_suspect") boolean isSuspect;
    }

    class SensorStatus {
        @SerializedName("sensor_id") int sensorId;
        @SerializedName("latest_reading") Reading latestReading;
    }

    class Recommendation {
        /** hot | cold | comfortable | unknown */
        String condition;
        Double temperature;
        @SerializedName("recommended_course_id") Integer recommendedCourseId;
        @SerializedName("recommended_course_name") String recommendedCourseName;
        List<CourseChoice> courses;
    }

    class CourseChoice {
        int id;
        @SerializedName("total_minutes") int totalMinutes;
        /** 코스 장소들의 평균 기온. 값이 없으면 null */
        @SerializedName("average_temperature") Double averageTemperature;
        boolean recommended;
    }

    class Course {
        int id;
        String name;
        String description;
        @SerializedName("total_minutes") int totalMinutes;
        List<CourseSpot> spots;
    }

    class CourseSpot {
        int position;
        int minutes;
        Spot spot;
    }

    class Spot {
        int id;
        String name;
        String description;
        Double latitude;
        Double longitude;
        Arrival arrival;
        @SerializedName("latest_reading") Reading latestReading;
        @SerializedName("value_source") ValueSource valueSource;
    }

    class Arrival {
        /** ble_auto: 센서로 자동 도착 판정, manual: 도착 버튼 */
        String method;
        /** 도착 판정에 쓰는, 이 장소에 설치된 센서. manual 이면 빈 목록 */
        List<SpotSensor> sensors;
    }

    class SpotSensor {
        @SerializedName("sensor_id") int sensorId;
    }

    class ValueSource {
        /** 값을 낸 센서의 설치 위치 */
        String location;
        /** false 면 가까운 센서 값을 빌려 쓴 것 */
        @SerializedName("is_on_site") boolean isOnSite;
        /** 장소와 센서 사이 거리(m). 모르면 null */
        @SerializedName("distance_m") Integer distanceM;
    }
}
