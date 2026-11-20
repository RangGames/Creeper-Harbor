package wiki.creeper.harbor.web.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.economy.api.AuctionService;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankingService;

/**
 * 모듈 서비스를 쓰는 경로 (시세·경매·기록·랭킹). 이 클래스는 그 모듈이 켜져 있을 때만 각 register를 부른다 (클래스를 불러도 모듈 api가 없으면 메서드 본문의
 * 참조에서만 실패하므로, 부르기 전에 {@link CoreRoutes#enabled}로 확인한다).
 */
public final class ModuleRoutes {

    private ModuleRoutes() {}

    // ------------------------------------------------------------------ Economy

    public static void economy(Router router, Calls calls) {
        router.get("market", "어종 시세 (계수 1.0 = 기준가, next = 다음 시간 예측)", r -> market(calls));
        router.get(
                "auctions",
                "경매 진행 중 목록 (?category=fish|gear|bait|material|food|other&limit=50)",
                r -> auctions(calls, r.query("category", ""), r.queryInt("limit", 50, 1, 200)));
    }

    private static Object market(Calls calls) throws Router.ApiException {
        PriceService prices = service(calls, PriceService.class);
        List<FishDef> fish =
                calls.api().content().snapshot().all(FishDef.TYPE).values().stream()
                        .sorted(Comparator.comparingInt(FishDef::no))
                        .toList();
        return calls.main(
                () -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (FishDef f : fish) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("fish", f.id().id().toString());
                        m.put("name", f.name());
                        m.put("coefficient", round(prices.coefficient(f.id())));
                        var next = prices.nextCoefficient(f.id());
                        if (next.isPresent()) {
                            m.put("next", round(next.getAsDouble()));
                        }
                        out.add(m);
                    }
                    return out;
                });
    }

    private static Object auctions(Calls calls, String category, int limit)
            throws Router.ApiException {
        AuctionService auctions = service(calls, AuctionService.class);
        return calls.await(auctions.active(category.isEmpty() ? null : category, limit));
    }

    // ------------------------------------------------------------------ Collection

    public static void collection(Router router, Calls calls) {
        router.get(
                "records",
                "어종 서버 기록 (?fish=<어종 ID>&league=tier1|tier2|all&limit=10)",
                r ->
                        records(
                                calls,
                                r.query("fish", ""),
                                r.query("league", "all"),
                                r.queryInt("limit", 10, 1, 10)));
    }

    private static Object records(Calls calls, String fish, String league, int limit)
            throws Router.ApiException {
        if (!RecordService.LEAGUES.contains(league)) {
            throw new Router.ApiException(
                    400, "bad_league", "league는 " + RecordService.LEAGUES + " 중 하나");
        }
        Optional<HarborId> id = HarborId.tryParse(fish).filter(h -> h.hasPrefix(FishId.PREFIX));
        if (id.isEmpty()
                || calls.api().content().snapshot().get(FishDef.TYPE, id.get()).isEmpty()) {
            throw new Router.ApiException(404, "fish_not_found", "없는 어종입니다: " + fish);
        }
        RecordService records = service(calls, RecordService.class);
        FishId fishId = new FishId(id.get());
        return calls.main(() -> records.serverTop(fishId, league, limit));
    }

    // ------------------------------------------------------------------ Season

    public static void season(Router router, Calls calls) {
        router.get("rankings", "랭킹 보드 목록", r -> boards(calls));
        router.get(
                "rankings/{board}",
                "랭킹 순위표 (?league=tier1|tier2|all, 10분 주기 집계 캐시)",
                r -> board(calls, r.param("board"), r.query("league", "all")));
    }

    private static Object boards(Calls calls) throws Router.ApiException {
        RankingService rankings = service(calls, RankingService.class);
        return calls.main(
                () ->
                        rankings.boards().stream()
                                .map(
                                        b -> {
                                            Map<String, Object> m = new LinkedHashMap<>();
                                            m.put("id", b.id());
                                            m.put("name", b.name());
                                            m.put(
                                                    "leagues",
                                                    b.leagues().stream().map(League::key).toList());
                                            m.put("size", b.size());
                                            return m;
                                        })
                                .toList());
    }

    private static Object board(Calls calls, String board, String leagueKey)
            throws Router.ApiException {
        RankingService rankings = service(calls, RankingService.class);
        League league = null;
        for (League l : League.values()) {
            if (l.key().equals(leagueKey)) {
                league = l;
            }
        }
        if (league == null) {
            throw new Router.ApiException(400, "bad_league", "없는 리그입니다: " + leagueKey);
        }
        League chosen = league;
        if (calls.main(() -> rankings.boardDef(board).isEmpty())) {
            throw new Router.ApiException(404, "board_not_found", "없는 보드입니다: " + board);
        }
        return calls.main(() -> rankings.board(board, chosen));
    }

    // ------------------------------------------------------------------ 도우미

    private static <T> T service(Calls calls, Class<T> type) throws Router.ApiException {
        return calls.api()
                .service(type)
                .orElseThrow(
                        () ->
                                new Router.ApiException(
                                        503, "unavailable", type.getSimpleName() + " 서비스가 없습니다"));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
