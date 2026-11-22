package wiki.creeper.harbor.web.http;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.web.relay.EventRelay;

/**
 * Core routes: server, player summary, player codex and quests (when those modules are enabled),
 * recent events, fish list. Player data is read from the DB (read-only), so for online players
 * progress not yet written may lag a few minutes (write-behind queue).
 */
public final class CoreRoutes {

    private CoreRoutes() {}

    public static void register(Router router, Calls calls, RecentEvents events) {
        router.get("server", "서버 ID·시즌·접속 인원", r -> server(calls));
        router.get(
                "players/{id}",
                "플레이어 요약 (id = UUID 또는 이름): 레벨·직업·골드·물비늘·평판·접속 시각",
                r -> player(calls, r.param("id")));
        router.get(
                "events",
                "최근 외부 이벤트 (?after=<seq>&type=<glob>&limit=100). 다음 요청은 마지막 seq를 after로",
                r ->
                        Map.of(
                                "last",
                                events.last(),
                                "items",
                                events.after(
                                        r.queryInt("after", 0, 0, Integer.MAX_VALUE),
                                        r.query("type", ""),
                                        r.queryInt("limit", 100, 1, 500))));
        router.get("content/fish", "어종 목록 (ID·번호·이름·등급·지역)", r -> fish(calls));
        router.get(
                "players/{id}/ledger",
                "재화 원장 최근 기록 (?currency=gold|mulbineul&limit=50, 최대 200)",
                r ->
                        ledger(
                                calls,
                                r.param("id"),
                                r.query("currency", "gold"),
                                r.queryInt("limit", 50, 1, 200)));
        if (enabled("Harbor-Collection")) {
            router.get(
                    "players/{id}/codex",
                    "플레이어 도감 (?scope=total|season)",
                    r -> codex(calls, r.param("id"), r.query("scope", "total")));
        }
        if (enabled("Harbor-Quest")) {
            router.get(
                    "players/{id}/quests",
                    "플레이어 퀘스트 (이번 시즌 + 메인)",
                    r -> quests(calls, r.param("id")));
        }
    }

    public static boolean enabled(String plugin) {
        var p = Bukkit.getPluginManager().getPlugin(plugin);
        return p != null && p.isEnabled();
    }

    private static Object server(Calls calls) throws Router.ApiException {
        int online = calls.main(() -> Bukkit.getOnlinePlayers().size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", calls.api().infra().serverId());
        out.put("season", calls.api().infra().season().value());
        out.put("online", online);
        return out;
    }

    /** UUID or name → UUID (names resolve by last login). */
    static UUID resolve(Calls calls, String id) throws Router.ApiException {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            if (!id.matches("[A-Za-z0-9_]{1,16}")) {
                throw new Router.ApiException(400, "bad_id", "UUID 또는 마인크래프트 이름이어야 합니다");
            }
        }
        java.util.Optional<UUID> found =
                calls.db(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT uuid FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1")) {
                                ps.setString(1, id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next()
                                            ? java.util.Optional.of(
                                                    UUID.fromString(rs.getString(1)))
                                            : java.util.Optional.<UUID>empty();
                                }
                            }
                        });
        if (found.isEmpty()) {
            throw new Router.ApiException(404, "player_not_found", "플레이어를 찾지 못했습니다: " + id);
        }
        return found.get();
    }

    private static Object player(Calls calls, String id) throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        int season = calls.api().infra().season().value();
        Map<String, Object> result =
                calls.db(
                        c -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT name, first_join, last_join, playtime FROM players WHERE uuid = ?")) {
                                ps.setString(1, uuid.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (!rs.next()) {
                                        return out;
                                    }
                                    out.put("uuid", uuid.toString());
                                    out.put("name", rs.getString(1));
                                    out.put(
                                            "first_join",
                                            rs.getTimestamp(2).toInstant().toString());
                                    out.put("last_join", rs.getTimestamp(3).toInstant().toString());
                                    out.put("playtime_seconds", rs.getLong(4));
                                }
                            }
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT job_line, job_tier, level, exp, gold, mulbineul_earned - mulbineul_spent, reputation"
                                                    + " FROM player_season WHERE uuid = ? AND season = ?")) {
                                ps.setString(1, uuid.toString());
                                ps.setInt(2, season);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        Map<String, Object> s = new LinkedHashMap<>();
                                        s.put("season", season);
                                        s.put("job_line", rs.getString(1));
                                        s.put("job_tier", rs.getInt(2));
                                        s.put("level", rs.getInt(3));
                                        s.put("exp", rs.getLong(4));
                                        s.put("gold", rs.getLong(5));
                                        s.put("mulbineul", rs.getLong(6));
                                        s.put("reputation", rs.getInt(7));
                                        out.put("season", s);
                                    }
                                }
                            }
                            return out;
                        });
        if (result.isEmpty()) {
            throw new Router.ApiException(404, "player_not_found", "플레이어를 찾지 못했습니다: " + id);
        }
        return result;
    }

    private static Object codex(Calls calls, String id, String scope) throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        boolean season = scope.equals("season");
        int seasonNo = calls.api().infra().season().value();
        int total = calls.api().content().snapshot().all(FishDef.TYPE).size();
        List<Map<String, Object>> entries =
                calls.db(
                        c -> {
                            String sql =
                                    season
                                            ? "SELECT fish_id, first_caught, max_size, best_quality, catch_count FROM codex_season WHERE uuid = ? AND season = ?"
                                            : "SELECT fish_id, first_caught, max_size, best_quality, catch_count FROM codex_total WHERE uuid = ?";
                            List<Map<String, Object>> list = new ArrayList<>();
                            try (PreparedStatement ps = c.prepareStatement(sql)) {
                                ps.setString(1, uuid.toString());
                                if (season) {
                                    ps.setInt(2, seasonNo);
                                }
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        Map<String, Object> e = new LinkedHashMap<>();
                                        e.put("fish", rs.getString(1));
                                        e.put(
                                                "first_caught",
                                                rs.getTimestamp(2).toInstant().toString());
                                        e.put("max_size", rs.getDouble(3));
                                        e.put("best_quality", rs.getInt(4));
                                        e.put("catch_count", rs.getInt(5));
                                        list.add(e);
                                    }
                                }
                            }
                            return list;
                        });
        entries.forEach(
                e ->
                        wiki.creeper.harbor.common.id.HarborId.tryParse(
                                        String.valueOf(e.get("fish")))
                                .flatMap(fid -> EventRelay.contentName(calls.api(), fid))
                                .ifPresent(n -> e.put("fish_name", n)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", season ? "season" : "total");
        out.put("registered", entries.size());
        out.put("total", total);
        out.put("percent", total == 0 ? 0 : Math.round(entries.size() * 1000.0 / total) / 10.0);
        out.put("entries", entries);
        return out;
    }

    private static Object quests(Calls calls, String id) throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        int season = calls.api().infra().season().value();
        List<Map<String, Object>> rows =
                calls.db(
                        c -> {
                            List<Map<String, Object>> list = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT quest_id, season, state, updated_at FROM quest_progress"
                                                    + " WHERE uuid = ? AND (season = ? OR quest_id LIKE 'harbor:quest/main/%')")) {
                                ps.setString(1, uuid.toString());
                                ps.setInt(2, season);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        Map<String, Object> q = new LinkedHashMap<>();
                                        q.put("quest", rs.getString(1));
                                        q.put("season", rs.getInt(2));
                                        q.put(
                                                "state",
                                                rs.getString(3).toLowerCase(java.util.Locale.ROOT));
                                        q.put(
                                                "updated_at",
                                                rs.getTimestamp(4).toInstant().toString());
                                        list.add(q);
                                    }
                                }
                            }
                            return list;
                        });
        rows.forEach(
                q ->
                        wiki.creeper.harbor.common.id.HarborId.tryParse(
                                        String.valueOf(q.get("quest")))
                                .flatMap(qid -> EventRelay.contentName(calls.api(), qid))
                                .ifPresent(n -> q.put("name", n)));
        return Map.of("quests", rows);
    }

    private static Object ledger(Calls calls, String id, String currencyKey, int limit)
            throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        wiki.creeper.harbor.common.model.Currency currency =
                switch (currencyKey) {
                    case "gold" -> wiki.creeper.harbor.common.model.Currency.GOLD;
                    case "mulbineul" -> wiki.creeper.harbor.common.model.Currency.MULBINEUL;
                    default ->
                            throw new Router.ApiException(
                                    400, "bad_currency", "currency는 gold 또는 mulbineul");
                };
        return calls.await(calls.api().currency().history(uuid, currency, limit));
    }

    private static Object fish(Calls calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        calls.api().content().snapshot().all(FishDef.TYPE).values().stream()
                .sorted(Comparator.comparingInt(FishDef::no))
                .forEach(
                        f -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", f.id().id().toString());
                            m.put("no", f.no());
                            m.put("name", f.name());
                            m.put("grade", f.grade().key());
                            m.put("region", f.region().id().toString());
                            out.add(m);
                        });
        return out;
    }
}
