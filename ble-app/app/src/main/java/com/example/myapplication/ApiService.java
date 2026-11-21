package com.example.myapplication;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * 연구실 서버(203.255.81.72:10021)로 측정값을 보내는 API.
 * 연구실 센서 공용 API 명세서 v4의 2번 API(센서 데이터 전송)이다.
 * 여기로 보낸 값은 연구실 최신값 API(1번)에 반영되고, 우리 서버가 1번 API에서 가져간다.
 */
public interface ApiService {

    /**
     * 데이터 전송이지만 GET 이다. 경로 끝의 / 는 붙여야 하고(빼면 301), 마지막 값 뒤에는 / 를 붙이지 않는다.
     * Retrofit이 쿼리를 경로 뒤에 붙이므로 결과는 /sensor/sensing/?mac=...&rssi=-58 이 된다.
     * 성공은 200 {"status": "success", ...}, 실패는 400 {"error": ...}.
     */
    @GET("sensor/sensing/")
    Call<SensingResponse> sendSensing(
            @Query("mac") String mac,
            @Query("sensor") String sensor,
            @Query("sender") String sender,
            @Query("mode") String mode,
            @Query("temp") double temp,
            @Query("co2") int co2,
            @Query("time") long time,
            @Query("rssi") int rssi);

    /** 재전송 큐에 저장된 측정값 한 건을 보내는 요청을 만든다. */
    static Call<SensingResponse> send(ApiService api, SensorRequest r) {
        return api.sendSensing(r.getMac(), r.getLabName(), r.getSender(), SensorRequest.MODE,
                r.getTemp(), r.getCo2(), r.getTimestamp(), r.getRssi());
    }

    /** 2번 API 응답 */
    class SensingResponse {
        String status;
        String message;
        Integer id;
        String error;

        @Override
        public String toString() {
            if (error != null) return "error: " + error;
            return status + " - " + message + (id != null ? " (id " + id + ")" : "");
        }
    }
}
