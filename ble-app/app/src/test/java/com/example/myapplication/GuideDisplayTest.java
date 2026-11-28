package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 가이드 화면 문구 (#64): 앱에 넣어 둔 기본 목록으로 장소 이름과 수가 설계와 맞는지 */
public class GuideDisplayTest {

    private static List<HomeApi.Course> defaults() throws IOException {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/" + TourData.DEFAULT_ASSET)),
                StandardCharsets.UTF_8);
        List<HomeApi.Course> courses = TourData.parse(json);
        assertNotNull("기본 목록을 읽지 못함", courses);
        return courses;
    }

    @Test
    public void autoAndManualSpots_matchSensors() throws IOException {
        // 우리 센서가 있는 4곳만 자동 도착, 나머지 8곳은 '도착' 버튼 (요구사항 명세서 2.5절)
        List<HomeApi.Course> courses = defaults();
        assertEquals(Arrays.asList("테니스장", "사회과학대 입구", "이마트24 건너편", "도서관 뒤편"),
                GuideDisplay.autoSpots(courses));
        assertEquals(8, GuideDisplay.manualCount(courses));
    }

    @Test
    public void cardAndCoreSpots() throws IOException {
        List<HomeApi.Course> courses = defaults();
        assertEquals(6, GuideDisplay.cardSpots(courses).size());
        assertEquals(Arrays.asList("도서관 뒤편", "솔못", "사회과학대 입구"), GuideDisplay.coreSpots(courses));
    }

    @Test
    public void join() {
        assertEquals("도서관 뒤편 · 솔못", GuideDisplay.join(Arrays.asList("도서관 뒤편", "", "솔못")));
        assertEquals("", GuideDisplay.join(Collections.emptyList()));
    }
}
