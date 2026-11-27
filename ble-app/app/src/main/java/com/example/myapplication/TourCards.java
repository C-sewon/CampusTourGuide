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
 * 카드는 장소마다 한 번만 띄운다. 띄운 카드는 폰에 저장하고(tour_cards.xml, 백업 제외),
 * 방문 기록이 지워진 장소는 다시 도착하면 카드를 또 띄운다.
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

    /** 아직 띄우지 않은 카드 장소 (도착한 순서대로) */
    static List<Integer> pending(List<Integer> visitedInOrder, Collection<Integer> cardIds, Collection<Integer> seen) {
        List<Integer> out = new ArrayList<>();
        for (Integer id : visitedInOrder) {
            if (id != null && cardIds.contains(id) && !seen.contains(id) && !out.contains(id)) out.add(id);
        }
        return out;
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
    private static final Set<Integer> SEEN = new LinkedHashSet<>();
    private static SharedPreferences prefs;

    /** 화면을 열 때 부른다. 이미 띄운 카드를 폰에서 읽어 온다 */
    public static synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SEEN.addAll(MyCourse.decode(prefs.getString(PREF_SEEN, null)));
    }

    /** 방문한 장소 번호 (TourProgress) */
    public static Set<Integer> visitedIds() {
        Set<Integer> ids = new HashSet<>();
        for (TourProgress.Visit v : TourProgress.visits()) ids.add(v.spotId);
        return ids;
    }

    /**
     * 지금 띄워야 할 카드 장소 (도착한 순서대로).
     * 방문 기록이 지워진 장소는 "띄움" 표시도 지워서, 다시 도착하면 카드가 또 뜨게 한다.
     */
    public static synchronized List<Integer> pendingNow(List<HomeApi.Course> courses) {
        List<Integer> order = new ArrayList<>();
        for (TourProgress.Visit v : TourProgress.visits()) order.add(v.spotId);
        if (SEEN.retainAll(order)) save();
        Set<Integer> cardIds = new HashSet<>();
        for (HomeApi.Spot s : cardSpots(courses)) cardIds.add(s.id);
        return pending(order, cardIds, SEEN);
    }

    public static synchronized void markSeen(int spotId) {
        if (SEEN.add(spotId)) save();
    }

    private static void save() {
        if (prefs != null) prefs.edit().putString(PREF_SEEN, MyCourse.encode(new ArrayList<>(SEEN))).apply();
    }

    /** 테스트용: 처음 상태로 */
    static synchronized void resetForTest() {
        SEEN.clear();
        prefs = null;
    }
}
