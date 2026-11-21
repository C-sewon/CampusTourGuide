package com.example.myapplication;

/**
 * 연구실 공용 센서 데이터 전송 API(2번)로 보낼 측정값 한 건.
 * 재전송 큐가 이 객체를 파일에 저장했다가 다시 보낸다.
 *
 * 전송 형식 (연구실 센서 공용 API 명세서 v4, 2번 API)
 * GET /sensor/sensing/?mac=&sensor=&sender=&mode=mobile&temp=&co2=&time=&rssi=
 * - sensor: 오픈소스 센서는 BLE 이름이 아니라 연구실 이름으로 보낸다. (opensrc1 → sensor 07)
 * - co2: 오픈소스 센서는 eCO2 값을 넣는다.
 * - 습도, AQI, TVOC는 2번 API에 칸이 없어서 보내지 않는다.
 */
public class SensorRequest {
    public static final String MODE = "mobile";

    /** BLE 이름 (opensrc1~5). 로그와 중복 확인용이며 전송하지 않는다. */
    private String bleName;
    /** 연구실 이름 (sensor 07 등) */
    private String sensor;
    private String mac;
    /** 전송한 스마트폰 ID */
    private String sender;
    /** 온도 (℃) */
    private double temp;
    /** eCO2 (ppm) */
    private int co2;
    /** 측정 시각 (Unix 초, 센서 시계) */
    private long time;
    /** 수신 신호 세기 (dBm) */
    private int rssi;

    public SensorRequest(String bleName, String labName, String mac, String sender,
                         double temp, int co2, long time, int rssi) {
        this.bleName = bleName;
        this.sensor = labName;
        this.mac = mac;
        this.sender = sender;
        this.temp = temp;
        this.co2 = co2;
        this.time = time;
        this.rssi = rssi;
    }

    public String getBleName() { return bleName; }
    public String getLabName() { return sensor; }
    public String getMac() { return mac; }
    public String getSender() { return sender; }
    public double getTemp() { return temp; }
    public int getCo2() { return co2; }
    public long getTimestamp() { return time; }
    public int getRssi() { return rssi; }
}
