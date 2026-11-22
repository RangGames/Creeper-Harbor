package wiki.creeper.harbor.web.http;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingBoard;
import wiki.creeper.harbor.season.api.RankingService;

/**
 * Routes that use module services or tables (market, auctions, records, rankings). Each register is
 * called only when its module is enabled (loading the class without the module api only fails at
 * the references in method bodies, so check {@link CoreRoutes#enabled} before calling).
 *
 * <p>Module records are mapped to explicit snake_case maps here (never serialized as-is), with
 * enum-like values as lowercase keys.
 */
public final class ModuleRoutes {

    static final List<String> CATEGORIES =
            List.of("all", "fish", "gear", "bait", "material", "food", "other");
    static final List<String> LEAGUES = List.of("tier1", "tier2", "all");

    /** Maximum auctions per page. */
    static final int AUCTION_MAX = 200;

    private ModuleRoutes() {}

    // ------------------------------------------------------------------ Economy

    public static void economy(Router router, Calls calls) {
        router.get("market", "어종 시세 (계수 1.0 = 기준가, next = 다음 시간 예측)", r -> market(calls));
        router.get(
                "auctions",
                "경매 진행 중 목록, 최신순 (?category=all|fish|gear|bait|material|food|other&limit=50&cursor=<next>)",
                r ->
                        auctions(
                                calls,
                                r.queryChoice("category", "all", CATEGORIES),
                                r.queryInt("limit", 50, 1, AUCTION_MAX),
                                r.queryLong("cursor", 0, 0, Long.MAX_VALUE)));
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

    /**
     * Active, unexpired listings newest first (id descending), read straight from the Economy
     * auction_listing table (read-only, no cache) so pages can go past the in-game screen's 45-row
     * page. Keyset pagination: cursor = the previous page's next (a listing id; listings with a
     * smaller id follow). next is absent on the last page.
     */
    private static Object auctions(Calls calls, String category, int limit, long cursor)
            throws Router.ApiException {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, seller, seller_name, item_id, item_name, category, amount, price,"
                                + " created_at, expires_at FROM auction_listing"
                                + " WHERE state = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(3)");
        if (!category.equals("all")) {
            sql.append(" AND category = ?");
        }
        if (cursor > 0) {
            sql.append(" AND id < ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        List<Map<String, Object>> rows =
                calls.db(
                        c -> {
                            List<Map<String, Object>> list = new ArrayList<>();
                            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                                int i = 1;
                                if (!category.equals("all")) {
                                    ps.setString(i++, category);
                                }
                                if (cursor > 0) {
                                    ps.setLong(i++, cursor);
                                }
                                ps.setInt(i, limit + 1); // one extra row tells whether more follow
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        Map<String, Object> m = new LinkedHashMap<>();
                                        m.put("id", rs.getLong(1));
                                        m.put("seller", rs.getString(2));
                                        m.put("seller_name", rs.getString(3));
                                        m.put("item_id", rs.getString(4));
                                        m.put("item_name", rs.getString(5));
                                        m.put("category", rs.getString(6));
                                        m.put("amount", rs.getInt(7));
                                        m.put("price", rs.getLong(8));
                                        m.put(
                                                "created_at",
                                                rs.getTimestamp(9).toInstant().toString());
                                        m.put(
                                                "expires_at",
                                                rs.getTimestamp(10).toInstant().toString());
                                        list.add(m);
                                    }
                                }
                            }
                            return list;
                        });
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            out.put("next", String.valueOf(rows.getLast().get("id")));
        }
        out.put("items", rows);
        return out;
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
                                r.queryChoice("league", "all", LEAGUES),
                                r.queryInt("limit", 10, 1, 10)));
    }

    private static Object records(Calls calls, String fish, String league, int limit)
            throws Router.ApiException {
        Optional<HarborId> id = HarborId.tryParse(fish).filter(h -> h.hasPrefix(FishId.PREFIX));
        if (id.isEmpty()
                || calls.api().content().snapshot().get(FishDef.TYPE, id.get()).isEmpty()) {
            throw new Router.ApiException(404, "fish_not_found", "없는 어종입니다: " + fish);
        }
        RecordService records = service(calls, RecordService.class);
        FishId fishId = new FishId(id.get());
        List<RecordEntry> top = calls.main(() -> records.serverTop(fishId, league, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 0;
        for (RecordEntry e : top) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", ++rank);
            m.put("player", e.player().toString());
            m.put("name", e.name());
            m.put("size", e.size());
            m.put("caught_at", e.caughtAt().toString());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ Season

    public static void season(Router router, Calls calls) {
        router.get("rankings", "랭킹 보드 목록", r -> boards(calls));
        router.get(
                "rankings/{board}",
                "랭킹 순위표 (?league=tier1|tier2|all, 10분 주기 집계 캐시)",
                r -> board(calls, r.param("board"), r.queryChoice("league", "all", LEAGUES)));
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
        Optional<League> league = League.parse(leagueKey);
        if (league.isEmpty()) {
            throw Router.ApiException.badParameter(
                    "league", "league는 " + String.join(", ", LEAGUES) + " 중 하나여야 합니다", LEAGUES);
        }
        League chosen = league.get();
        if (calls.main(() -> rankings.boardDef(board).isEmpty())) {
            throw new Router.ApiException(404, "board_not_found", "없는 보드입니다: " + board);
        }
        RankingBoard b = calls.main(() -> rankings.board(board, chosen));
        return boardJson(b);
    }

    /** RankingBoard → snake_case map (league as lowercase key). */
    static Map<String, Object> boardJson(RankingBoard b) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RankEntry e : b.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", e.rank());
            var uuid = e.uuid();
            if (uuid != null) {
                m.put("uuid", uuid.toString());
            }
            m.put("name", e.name());
            m.put("value", e.value());
            m.put("secondary", e.secondary());
            m.put("achieved_at", e.achievedAt().toString());
            m.put("members", e.members());
            entries.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("board", b.board());
        out.put("league", b.league().key());
        out.put("season", b.season());
        out.put("computed_at", b.computedAt().toString());
        out.put("entries", entries);
        return out;
    }

    // ------------------------------------------------------------------ helpers

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
