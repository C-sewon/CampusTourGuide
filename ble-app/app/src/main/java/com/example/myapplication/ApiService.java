package com.example.myapplication;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface ApiService {
    @POST("sensor/opensrc/upload/")
    Call<SensorResponse> sendSensorData(@Body SensorRequest request);
}