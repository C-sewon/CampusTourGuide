package com.example.myapplication;

import com.google.gson.annotations.SerializedName;

public class SensorRequest {
    private String key;
    private String sensor;
    private String mac;
    private double temp;
    private double humidity;
    @SerializedName("AQI")
    private double AQI;
    @SerializedName("TVOC")
    private double TVOC;
    @SerializedName("eCO2")
    private double eCO2;
    private long timestamp;
    private double lat;
    private double lon;
    private String sender;
    private int rssi;

    public SensorRequest(String key, String sensor, String mac,
                         double temp, double humidity, double AQI, double TVOC, double eCO2,
                         long timestamp, double lat, double lon, String sender, int rssi) {
        this.key = key;
        this.sensor = sensor;
        this.mac = mac;
        this.temp = temp;
        this.humidity = humidity;
        this.AQI = AQI;
        this.TVOC = TVOC;
        this.eCO2 = eCO2;
        this.timestamp = timestamp;
        this.lat = lat;
        this.lon = lon;
        this.sender = sender;
        this.rssi = rssi;
    }
}