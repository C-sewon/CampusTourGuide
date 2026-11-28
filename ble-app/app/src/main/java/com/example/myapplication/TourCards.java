package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 장소 카드 (#37, 요구사항 RF-FX-03). 도착이 기록된 장소의 has_card 가 true 이면 카드를 띄운다.
 * - 핵심 코스(is_core) 장소: "핵심 코스 3곳 중 M곳 방문" 카드. 핵심 코스 장소를 모두 방문하면 완주 카드
 * - 그 밖의 카드 장소(개신문화관, 박물관, 야외공연장): 장소 소개 카드
 * 도착은 BLE 자동 도착이든 "도착" 버튼이든 같다. 어느 코스로 걷든 카드 장소에 도착하면 카드가 뜬다.
 *
 * 카드는 방문(장소 + 도착 시각)마다 한 번만 띄운다. 띄운 방문은 폰에 저장하고(tour_cards.xml, 백업 제외),
 * 방문 기록이 지워졌다가 다시 도착하면 도착 시각이 달라 새 방문이므로 카드를 또 띄운다. (PR #65 리뷰 2)
 * 밀린 카드를 여러 장 띄울 때는 각 카드를 "그 장소에 도착한 때까지의 방문"으로 만든다. (PR #65 리뷰 1)
 * 계산은 Android 없이 테스트할 수 있게 static 함수로 둔다. (TourCardsTest)
 */
public final class TourCards {

    public enum Kind {
        /** 핵심 코스 장소 카드 */
        CORE,
        /** 핵심 코스를 모두 방문했을 때의 완주 카드 */
        COMPLETE,
        /** 장소 소개 카드 */
        INTRO
    }

    /** 띄울 카드 한 장 */
    public static final class Card {
        public final Kind kind;
        public final HomeApi.Spot spot;
        /** 핵심 코스 장소 이름과 방문 여부 (코스 순서). 핵심 코스 카드가 아니면 빈 목록 */
        public final List<String> coreNames;
        public final List<Boolean> coreVisited;
        /** 모은 카드 수 / 전체 카드 수 (도감) */
        public final int collected, total;
        /** 이 카드를 띄운 방문 ("장소번호:도착시각"). 봤다고 기록할 때 쓴다 */
        String visitKey;

        Card(Kind kind, HomeApi.Spot spot, List<String> coreNames, List<Boolean> coreVisited, int collected, int total) {
            this.kind = kind;
            this.spot = spot;
            this.coreNames = coreNames;
            this.coreVisited = coreVisited;
            this.collected = collected;
            this.total = total;
        }

        public int coreVisitedCount() {
            int n = 0;
            for (Boolean b : coreVisited) if (Boolean.TRUE.equals(b)) n++;
            return n;
        }
    }

    private TourCards() {}

    // ---------- 계산 ----------

    /** 카드가 있는 장소 (장소 번호 순서, 한 번씩) */
    public static List<HomeApi.Spot> cardSpots(List<HomeApi.Course> courses) {
        List<HomeApi.Spot> out = new ArrayList<>();
        for (HomeApi.Spot s : MyCourse.allSpots(courses)) if (s.hasCard) out.add(s);
        return out;
    }

    /** 핵심 코스(is_core)의 장소 (코스 순서). 핵심 코스가 없으면 빈 목록 */
    public static List<HomeApi.Spot> coreSpots(List<HomeApi.Course> courses) {
        List<HomeApi.Spot> out = new ArrayList<>();
        if (courses == null) return out;
        for (HomeApi.Course c : courses) {
            if (!c.isCore || c.spots == null) continue;
            for (HomeApi.CourseSpot cs : c.spots) if (cs.spot != null) out.add(cs.spot);
            break;  // 핵심 코스는 하나
        }
        return out;
    }

    /** 모은 카드 수: 카드 장소 중 방문한 곳 */
    public static int collected(List<HomeApi.Course> courses, Collection<Integer> visited) {
        int n = 0;
        for (HomeApi.Spot s : cardSpots(courses)) if (visited.contains(s.id)) n++;
        return n;
    }

    /** 이 장소에 도착했을 때 띄울 카드. 카드 장소가 아니면 null */
    public static Card cardFor(int spotId, List<HomeApi.Course> courses, Collection<Integer> visited) {
        HomeApi.Spot spot = null;
        List<HomeApi.Spot> cards = cardSpots(courses);
        for (HomeApi.Spot s : cards) if (s.id == spotId) spot = s;
        if (spot == null) return null;

        List<String> names = new ArrayList<>();
        List<Boolean> done = new ArrayList<>();
        boolean isCore = false;
        for (HomeApi.Spot s : coreSpots(courses)) {
            names.add(s.name);
            done.add(visited.contains(s.id));
            if (s.id == spotId) isCore = true;
        }
        int collected = collected(courses, visited);
        if (!isCore) {
            return new Card(Kind.INTRO, spot, Collections.emptyList(), Collections.emptyList(), collected, cards.size());
        }
        boolean all = !done.contains(false);
        return new Card(all ? Kind.COMPLETE : Kind.CORE, spot, names, done, collected, cards.size());
    }

    /** 방문 하나를 가리키는 글자: "장소번호:도착시각" */
    static String key(TourProgress.Visit v) {
        return v.spotId + ":" + v.arrivedAt;
    }

    /** 아직 띄우지 않은 카드 장소의 방문 (도착한 순서대로) */
    static List<TourProgress.Visit> pending(List<TourProgress.Visit> visitsInOrder, Collection<Integer> cardIds,
                                            Collection<String> seen) {
        List<TourProgress.Visit> out = new ArrayList<>();
        for (TourProgress.Visit v : visitsInOrder) {
            if (cardIds.contains(v.spotId) && !seen.contains(key(v))) out.add(v);
        }
        return out;
    }

    /** 이 방문까지(포함) 도착한 장소. 밀린 카드도 도착한 그때의 진행(1/3, 2/3, 완주)으로 보여 주려고 쓴다 */
    static Set<Integer> visitedUpTo(List<TourProgress.Visit> visitsInOrder, TourProgress.Visit upTo) {
        Set<Integer> ids = new HashSet<>();
        for (TourProgress.Visit v : visitsInOrder) {
            ids.add(v.spotId);
            if (v == upTo || key(v).equals(key(upTo))) break;
        }
        return ids;
    }

    /** 소개 글의 첫 문장 (카드에는 한 줄만). 글이 없으면 빈 글자 */
    public static String firstSentence(String text) {
        if (text == null) return "";
        String t = text.trim();
        int end = t.indexOf(". ");
        return end < 0 ? t : t.substring(0, end + 1);
    }

    /** 예: "핵심 코스 3곳 중 2곳 방문" */
    public static String coreProgress(int visited, int total) {
        return "핵심 코스 " + total + "곳 중 " + visited + "곳 방문";
    }

    /** 완주 카드 소개. 예: "도서관 뒤편 · 솔못 · 사회과학대 입구, 핵심 코스 3곳을 모두 걸었어요." */
    public static String completeIntro(List<String> coreNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : coreNames) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name);
        }
        return sb + ", 핵심 코스 " + coreNames.size() + "곳을 모두 걸었어요.";
    }

    /** 예: "도감 3/6장" */
    public static String collection(int collected, int total) {
        return "도감 " + collected + "/" + total + "장";
    }

    /** 홈 인사말 아래 줄. 아직 모은 카드가 없으면 null (기본 문구를 쓴다) */
    public static String homeSubline(int collected, int total) {
        if (collected <= 0 || total <= 0) return null;
        if (collected >= total) return "도감 " + total + "장을 모두 채웠어요!";
        return "도감 " + collected + "/" + total + "장 채웠어요 · 다음 카드도 열어 볼까요?";
    }

    // ---------- 띄운 카드 기억 (폰 저장) ----------

    private static final String PREFS = "tour_cards";
    private static final String PREF_SEEN = "seen";
    /** 띄운 방문 ("장소번호:도착시각") */
    private static final Set<String> SEEN = new LinkedHashSet<>();
    private static SharedPreferences prefs;

    /** 화면을 열 때 부른다. 이미 띄운 카드를 폰에서 읽어 온다 */
    public static synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_SEEN, null);
        if (saved != null && !saved.isEmpty()) {
            for (String k : saved.split(",")) if (k.contains(":")) SEEN.add(k.trim());
        }
    }

    /** 방문한 장소 번호 (TourProgress) */
    public static Set<Integer> visitedIds() {
        Set<Integer> ids = new HashSet<>();
        for (TourProgress.Visit v : TourProgress.visits()) ids.add(v.spotId);
        return ids;
    }

    /** 다음에 띄울 카드. 없으면 null. 띄운 뒤 닫히면 markSeen(card) 를 부른다 */
    public static synchronized Card nextCard(List<HomeApi.Course> courses) {
        List<TourProgress.Visit> visits = TourProgress.visits();
        // 지금 남아 있는 방문의 표시만 남긴다 (지워진 방문의 표시는 필요 없음)
        Set<String> alive = new HashSet<>();
        for (TourProgress.Visit v : visits) alive.add(key(v));
        if (SEEN.retainAll(alive)) save();

        Set<Integer> cardIds = new HashSet<>();
        for (HomeApi.Spot s : cardSpots(courses)) cardIds.add(s.id);
        List<TourProgress.Visit> pending = pending(visits, cardIds, SEEN);
        if (pending.isEmpty()) return null;
        TourProgress.Visit v = pending.get(0);
        Card card = cardFor(v.spotId, courses, visitedUpTo(visits, v));
        if (card != null) card.visitKey = key(v);
        return card;
    }

    /** 이 카드를 봤다고 기록한다 (사용자가 닫았을 때) */
    public static synchronized void markSeen(Card card) {
        if (card != null && card.visitKey != null && SEEN.add(card.visitKey)) save();
    }

    private static void save() {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String k : SEEN) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        prefs.edit().putString(PREF_SEEN, sb.toString()).apply();
    }

    /** 테스트용: 처음 상태로 */
    static synchronized void resetForTest() {
        SEEN.clear();
        prefs = null;
    }
}
