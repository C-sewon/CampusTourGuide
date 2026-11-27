package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 장소 카드 (#37 RF-FX-03): 어떤 카드를 띄울지, 몇 장 모았는지 */
public class TourCardsTest {

    // 핵심 코스: 도서관 뒤편(6) → 솔못(7) → 사회과학대 입구(4). 장소 소개 카드: 박물관(10). 카드 없음: 정문(1)
    private static final List<HomeApi.Course> COURSES = Arrays.asList(
            course(1, "핵심 코스", true,
                    spot(6, "도서관 뒤편", true, "중앙도서관(N12) 뒤편이에요. 1층에는 쉼터가 있어요."),
                    spot(7, "솔못", true, "캠퍼스 한가운데 있는 연못이에요."),
                    spot(4, "사회과학대 입구", true, null)),
            course(2, "정문 코스", false,
                    spot(1, "정문", false, "정문이에요."),
                    spot(4, "사회과학대 입구", true, null),
                    spot(6, "도서관 뒤편", true, "중앙도서관(N12) 뒤편이에요. 1층에는 쉼터가 있어요.")),
            course(3, "학생 생활 코스", false,
                    spot(10, "박물관", true, "1970년에 문을 연 박물관(S9)이에요. 구석기 전시실이 있어요.")));

    @Test
    public void cardSpots_uniqueByIdOnlyCards() {
        List<Integer> ids = new ArrayList<>();
        for (HomeApi.Spot s : TourCards.cardSpots(COURSES)) ids.add(s.id);
        assertEquals(Arrays.asList(4, 6, 7, 10), ids);
    }

    @Test
    public void coreSpots_inCourseOrder() {
        List<String> names = new ArrayList<>();
        for (HomeApi.Spot s : TourCards.coreSpots(COURSES)) names.add(s.name);
        assertEquals(Arrays.asList("도서관 뒤편", "솔못", "사회과학대 입구"), names);
    }

    @Test
    public void cardFor_noCardSpot_null() {
        assertNull(TourCards.cardFor(1, COURSES, set(1)));
    }

    @Test
    public void cardFor_coreSpot_countsCoreVisits() {
        TourCards.Card card = TourCards.cardFor(7, COURSES, set(6, 7, 1));
        assertEquals(TourCards.Kind.CORE, card.kind);
        assertEquals(2, card.coreVisitedCount());
        assertEquals(Arrays.asList(true, true, false), card.coreVisited);
        assertEquals(2, card.collected);
        assertEquals(4, card.total);
    }

    @Test
    public void cardFor_lastCoreSpot_complete() {
        // 어느 코스로 걸었든 핵심 코스 3곳을 모두 가면 완주 카드
        TourCards.Card card = TourCards.cardFor(4, COURSES, set(6, 7, 4));
        assertEquals(TourCards.Kind.COMPLETE, card.kind);
        assertEquals(3, card.coreVisitedCount());
    }

    @Test
    public void cardFor_otherCardSpot_intro() {
        TourCards.Card card = TourCards.cardFor(10, COURSES, set(10));
        assertEquals(TourCards.Kind.INTRO, card.kind);
        assertTrue(card.coreNames.isEmpty());
        assertEquals(1, card.collected);
    }

    @Test
    public void pending_arrivalOrder_skipsSeenAndNonCards() {
        List<Integer> visited = Arrays.asList(1, 7, 10, 6);
        Set<Integer> cardIds = set(4, 6, 7, 10);
        assertEquals(Arrays.asList(7, 10, 6), TourCards.pending(visited, cardIds, Collections.emptySet()));
        assertEquals(Collections.singletonList(6), TourCards.pending(visited, cardIds, set(7, 10)));
    }

    @Test
    public void collected_countsVisitedCardSpots() {
        assertEquals(0, TourCards.collected(COURSES, set(1)));
        assertEquals(3, TourCards.collected(COURSES, set(1, 4, 6, 10)));
    }

    @Test
    public void firstSentence_oneLine() {
        assertEquals("중앙도서관(N12) 뒤편이에요.", TourCards.firstSentence("중앙도서관(N12) 뒤편이에요. 1층에는 쉼터가 있어요."));
        assertEquals("캠퍼스 한가운데 있는 연못이에요.", TourCards.firstSentence("캠퍼스 한가운데 있는 연못이에요."));
        assertEquals("", TourCards.firstSentence(null));
    }

    @Test
    public void texts() {
        assertEquals("핵심 코스 3곳 중 2곳 방문", TourCards.coreProgress(2, 3));
        assertEquals("도감 3/6장", TourCards.collection(3, 6));
        assertEquals("도서관 뒤편 · 솔못 · 사회과학대 입구, 핵심 코스 3곳을 모두 걸었어요.",
                TourCards.completeIntro(Arrays.asList("도서관 뒤편", "솔못", "사회과학대 입구")));
        assertNull(TourCards.homeSubline(0, 6));
        assertEquals("도감 2/6장 채웠어요 · 다음 카드도 열어 볼까요?", TourCards.homeSubline(2, 6));
        assertEquals("도감 6장을 모두 채웠어요!", TourCards.homeSubline(6, 6));
    }

    // ---------- 테스트용 데이터 ----------

    private static Set<Integer> set(Integer... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static HomeApi.Spot spot(int id, String name, boolean hasCard, String description) {
        HomeApi.Spot s = new HomeApi.Spot();
        s.id = id;
        s.name = name;
        s.hasCard = hasCard;
        s.description = description;
        return s;
    }

    private static HomeApi.Course course(int id, String name, boolean core, HomeApi.Spot... spots) {
        HomeApi.Course c = new HomeApi.Course();
        c.id = id;
        c.name = name;
        c.isCore = core;
        c.spots = new ArrayList<>();
        int position = 1;
        for (HomeApi.Spot s : spots) {
            HomeApi.CourseSpot cs = new HomeApi.CourseSpot();
            cs.position = position++;
            cs.spot = s;
            c.spots.add(cs);
        }
        return c;
    }
}
