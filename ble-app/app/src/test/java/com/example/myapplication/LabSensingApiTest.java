package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 연구실 2번 API로 보내는 주소가 명세서(v4) 형식과 같은지 확인한다. 실제로 보내지는 않는다.
 */
public class LabSensingApiTest {

    private static Request build(SensorRequest r) {
        ApiService api = new Retrofit.Builder()
                .baseUrl("http://203.255.81.72:10021/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService.class);
        return ApiService.send(api, r).request();
    }

    @Test
    public void requestMatchesSpec() {
        Request req = build(new SensorRequest("opensrc1", "sensor 07", "88:A2:9E:9B:5F:09",
                "e433d61093eba2b1", 21.37, 620, 1790600000L, -58));
        HttpUrl url = req.url();

        assertEquals("GET", req.method());
        assertEquals("203.255.81.72", url.host());
        assertEquals(10021, url.port());
        // 경로 끝의 / 가 있어야 301 리다이렉트가 나지 않는다
        assertEquals("/sensor/sensing/", url.encodedPath());

        assertEquals("88:A2:9E:9B:5F:09", url.queryParameter("mac"));
        assertEquals("sensor 07", url.queryParameter("sensor"));
        assertEquals("e433d61093eba2b1", url.queryParameter("sender"));
        assertEquals("mobile", url.queryParameter("mode"));
        assertEquals("21.37", url.queryParameter("temp"));
        assertEquals("620", url.queryParameter("co2"));
        assertEquals("1790600000", url.queryParameter("time"));
        assertEquals("-58", url.queryParameter("rssi"));
    }

    @Test
    public void lastValueHasNoTrailingSlash() {
        // 마지막 값 뒤에 / 를 붙이면 rssi 값에 포함되어 저장에 실패한다 (명세서 주의 사항)
        String url = build(new SensorRequest("opensrc5", "sensor 33", "D8:3A:DD:C1:89:5B",
                "phone", 19.5, 600, 1790600000L, -70)).url().toString();
        assertTrue(url.endsWith("rssi=-70"));
        assertFalse(url.endsWith("/"));
    }

    @Test
    public void noLocationOrHumidityIsSent() {
        HttpUrl url = build(new SensorRequest("opensrc2", "sensor 15", "88:A2:9E:9B:5E:6A",
                "phone", 20.0, 600, 1790600000L, -60)).url();
        assertEquals(null, url.queryParameter("lat"));
        assertEquals(null, url.queryParameter("lon"));
        assertEquals(null, url.queryParameter("humidity"));
        assertEquals(null, url.queryParameter("key"));
    }

    @Test
    public void sensorsHaveLabNamesFromAppendixB() {
        assertEquals("sensor 07", Sensors.byId(1).labName);
        assertEquals("sensor 15", Sensors.byId(2).labName);
        assertEquals("sensor 31", Sensors.byId(3).labName);
        assertEquals("sensor 32", Sensors.byId(4).labName);
        assertEquals("sensor 33", Sensors.byId(5).labName);
    }
}
