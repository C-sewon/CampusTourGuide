package com.example.myapplication;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HomeDisplayTest {

    @Test
    public void reason_hot() {
        assertEquals("지금 31°C · 더운 날엔 짧은 코스를 추천해요", HomeDisplay.reason("hot", 31.0));
    }

    @Test
    public void reason_cold_keepsOneDecimal() {
        assertEquals("지금 3.5°C · 추운 날엔 짧은 코스를 추천해요", HomeDisplay.reason("cold", 3.5));
    }

    @Test
    public void reason_comfortable() {
        assertEquals("지금 21°C · 걷기 좋은 날이라 긴 코스를 추천해요", HomeDisplay.reason("comfortable", 21.0));
    }

    @Test
    public void reason_unknownOrNoTemperature() {
        String expected = "지금은 기온을 알 수 없어 기본 코스를 추천해요";
        assertEquals(expected, HomeDisplay.reason("unknown", null));
        assertEquals(expected, HomeDisplay.reason("hot", null));
        assertEquals(expected, HomeDisplay.reason(null, 20.0));
    }

    @Test
    public void tone_byCondition() {
        assertEquals(HomeDisplay.Tone.WARM, HomeDisplay.tone("hot"));
        assertEquals(HomeDisplay.Tone.WARM, HomeDisplay.tone("cold"));
        assertEquals(HomeDisplay.Tone.GOOD, HomeDisplay.tone("comfortable"));
        assertEquals(HomeDisplay.Tone.NEUTRAL, HomeDisplay.tone("unknown"));
    }

    @Test
    public void sensorTone_matchesServerThresholds() {
        assertEquals(HomeDisplay.SensorTone.HOT, HomeDisplay.sensorTone(27.0));
        assertEquals(HomeDisplay.SensorTone.COMFORTABLE, HomeDisplay.sensorTone(26.9));
        assertEquals(HomeDisplay.SensorTone.COMFORTABLE, HomeDisplay.sensorTone(5.1));
        assertEquals(HomeDisplay.SensorTone.COLD, HomeDisplay.sensorTone(5.0));
        assertEquals(HomeDisplay.SensorTone.NO_VALUE, HomeDisplay.sensorTone(null));
    }

    @Test
    public void sensorLabel() {
        assertEquals("센서 1 · 도서관 뒤편 · 22°C", HomeDisplay.sensorLabel(1, "도서관 뒤편", 22.0));
        assertEquals("센서 3 · 이마트24 건너편 · 지금은 값을 받을 수 없어요",
                HomeDisplay.sensorLabel(3, "이마트24 건너편", null));
    }

    @Test
    public void meta() {
        assertEquals("도보 약 12분 · 장소 2곳", HomeDisplay.meta(12, 2));
        assertEquals("도보 약 12분", HomeDisplay.meta(12, 0));
    }

    @Test
    public void sensorLabel_fromServer() {
        assertEquals("센서 1 · 도서관 뒤편 · 22.5°C · 3분 전",
                HomeDisplay.sensorLabel(1, "도서관 뒤편", true, 22.5, 3, false, false));
        assertEquals("센서 1 · 도서관 뒤편 · 마지막 값 22°C (2시간 전)",
                HomeDisplay.sensorLabel(1, "도서관 뒤편", true, 22.0, 130, true, false));
        assertEquals("센서 1 · 도서관 뒤편 · 값을 점검하고 있어요",
                HomeDisplay.sensorLabel(1, "도서관 뒤편", true, 22.0, 3, false, true));
        assertEquals("센서 1 · 도서관 뒤편 · 아직 값이 없어요",
                HomeDisplay.sensorLabel(1, "도서관 뒤편", false, 0, 0, false, false));
    }

    @Test
    public void ago() {
        assertEquals("방금", HomeDisplay.ago(0));
        assertEquals("59분 전", HomeDisplay.ago(59));
        assertEquals("1시간 전", HomeDisplay.ago(60));
        assertEquals("2일 전", HomeDisplay.ago(60 * 48));
    }

    @Test
    public void normalizeBaseUrl() {
        assertEquals("http://192.168.0.5:8000/", HomeDisplay.normalizeBaseUrl(" 192.168.0.5:8000 "));
        assertEquals("http://192.168.0.5:8000/", HomeDisplay.normalizeBaseUrl("http://192.168.0.5:8000/"));
        assertEquals("", HomeDisplay.normalizeBaseUrl("  "));
    }
}
