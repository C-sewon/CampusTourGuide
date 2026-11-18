package com.example.myapplication;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpotDisplayTest {

    @Test
    public void progress() {
        assertEquals("01 / 04", SpotDisplay.progress(0, 4));
        assertEquals("12 / 12", SpotDisplay.progress(11, 12));
    }

    @Test
    public void banner() {
        assertEquals(SpotDisplay.Banner.WAITING, SpotDisplay.banner("ble_auto", false));
        assertEquals(SpotDisplay.Banner.MANUAL_NEEDED, SpotDisplay.banner("manual", false));
        assertEquals(SpotDisplay.Banner.ARRIVED, SpotDisplay.banner("manual", true));
        assertEquals(SpotDisplay.Banner.ARRIVED, SpotDisplay.banner("ble_auto", true));
    }

    @Test
    public void bannerText() {
        assertEquals("장소에 가까워지고 있어요",
                SpotDisplay.bannerTitle(SpotDisplay.Banner.WAITING, false));
        assertEquals("이 장소는 '도착' 버튼으로 체크해요",
                SpotDisplay.bannerTitle(SpotDisplay.Banner.MANUAL_NEEDED, false));
        assertEquals("도착했어요!", SpotDisplay.bannerTitle(SpotDisplay.Banner.ARRIVED, false));
        assertEquals("도착 체크했어요", SpotDisplay.bannerTitle(SpotDisplay.Banner.ARRIVED, true));
        assertEquals("BLE 센서가 장소를 인식했습니다.",
                SpotDisplay.bannerSub(SpotDisplay.Banner.ARRIVED, false));
        assertEquals("'도착' 버튼으로 방문을 기록했어요.",
                SpotDisplay.bannerSub(SpotDisplay.Banner.ARRIVED, true));
    }

    @Test
    public void source() {
        assertEquals("이 장소 센서 · 3분 전 측정", SpotDisplay.source(true, null, 3));
        assertEquals("약 56m 떨어진 센서 기준 · 3분 전 측정", SpotDisplay.source(false, 56, 3));
        assertEquals("가까운 센서 기준 · 방금 측정", SpotDisplay.source(false, null, 0));
        assertEquals("35분 전 측정 · 최신 값이 아닐 수 있어요", SpotDisplay.staleSource(35));
    }

    @Test
    public void distance() {
        assertEquals("거의 다 왔어요", SpotDisplay.distance(12));
        assertEquals("남은 거리 약 120m", SpotDisplay.distance(123.4));
        assertEquals("남은 거리 약 990m", SpotDisplay.distance(994));
        assertEquals("남은 거리 약 1.2km", SpotDisplay.distance(1234));
    }

    @Test
    public void buttonsAndWalk() {
        assertEquals("도보 4분", SpotDisplay.walk(4));
        assertEquals("다음 장소로", SpotDisplay.nextLabel(false));
        assertEquals("투어 마치기", SpotDisplay.nextLabel(true));
        assertEquals("도착", SpotDisplay.arriveLabel(false));
        assertEquals("도착 완료", SpotDisplay.arriveLabel(true));
    }
}
