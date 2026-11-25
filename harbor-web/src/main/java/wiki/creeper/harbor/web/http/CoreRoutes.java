package wiki.creeper.harbor.web.http;

import io.lettuce.core.RedisException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.session.LockState;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.api.LedgerRecord;

/**
 * Core routes: server, player summary, recent events, fish list, plus the player collection and
 * quests paths when those modules are enabled (their handlers are module bridges in {@link
 * ModuleRoutes}). Player data is read from the DB (read-only; Common players and Core
 * player_season), so for online players progress not yet written may lag a few minutes
 * (write-behind queue).
 *
 * <p>Every response field is snake_case and enum-like values are lowercase keys (the same values
 * the query parameters take).
 */
public final class CoreRoutes {

    static final List<String> CURRENCIES = List.of("gold", "mulbineul");
    static final List<String> SCOPES = List.of("total", "season");

    private CoreRoutes() {}

    public static void register(
            Router router, Calls calls, RecentEvents events, NetworkOnline network) {
        router.get(
                "server",
                "서버 ID·시즌·접속 인원 (online = 네트워크 전체, server_online = 이 서버)",
                r -> server(calls, network));
        router.get(
                "players/{id}",
                "플레이어 요약 (id = UUID 또는 이름): 레벨·직업·골드·물비늘·평판·접속 시각",
                r -> player(calls, r.param("id")));
        router.get(
                "events",
                "이 서버가 받은 최근 외부 이벤트 (?after=<seq>&type=<glob>&limit=100). 다음 요청은 next를 after로",
                r ->
                        events(
                                calls,
                                events.page(
                                        r.queryLong("after", 0, 0, Long.MAX_VALUE),
                                        r.query("type", ""),
                                        r.queryInt("limit", 100, 1, 500))));
        router.get("content/fish", "어종 목록 (ID·번호·이름·등급·지역)", r -> fish(calls));
        router.get(
                "players/{id}/online",
                "접속 여부 (네트워크 전체, 세션 잠금 기준): online·server·moving",
                r -> online(calls, r.param("id")));
        router.get(
                "players/{id}/ledger",
                "재화 원장 최근 기록 (?currency=gold|mulbineul&limit=50, 최대 200)",
                r ->
                        ledger(
                                calls,
                                r.param("id"),
                                r.queryChoice("currency", "gold", CURRENCIES),
                                r.queryInt("limit", 50, 1, 200)));
        if (enabled("Harbor-Collection")) {
            router.get(
                    "players/{id}/collection",
                    "플레이어 도감 (?scope=total|season)",
                    r ->
                            ModuleRoutes.playerCollection(
                                    calls, r.param("id"), r.queryChoice("scope", "total", SCOPES)));
        }
        if (enabled("Harbor-Quest")) {
            router.get(
                    "players/{id}/quests",
                    "플레이어 퀘스트 (이번 시즌 + 이월된 메인, 퀘스트마다 한 줄)",
                    r -> ModuleRoutes.playerQuests(calls, r.param("id")));
        }
    }

    public static boolean enabled(String plugin) {
        var p = Bukkit.getPluginManager().getPlugin(plugin);
        return p != null && p.isEnabled();
    }

    private static Object server(Calls calls, NetworkOnline network) throws Router.ApiException {
        int here = calls.main(() -> Bukkit.getOnlinePlayers().size());
        NetworkOnline.Snapshot net = network.snapshot(System.currentTimeMillis());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", calls.api().infra().serverId());
        out.put("season", calls.api().infra().season().value());
        out.put("online", net.online().orElse(here));
        out.put("online_scope", net.online().isPresent() ? "network" : "server");
        out.put("servers_reporting", net.servers());
        out.put("server_online", here);
        return out;
    }

    private static Object events(Calls calls, RecentEvents.Page page) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (RecentEvents.Item i : page.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", i.seq());
            m.put("event", i.event()); // Common wire form (snake_case)
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", calls.api().infra().serverId());
        out.put("last", page.last());
        out.put("next", page.next());
        out.put("items", items);
        return out;
    }

    /**
     * UUID or name → UUID of a player with a players row (names resolve by last login). 400 bad_id
     * for neither form, 404 player_not_found when there is no such player.
     */
    static UUID resolve(Calls calls, String id) throws Router.ApiException {
        UUID uuid = null;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            if (!id.matches("[A-Za-z0-9_]{1,16}")) {
                throw new Router.ApiException(400, "bad_id", "UUID 또는 마인크래프트 이름이어야 합니다");
            }
        }
        UUID byUuid = uuid;
        Optional<UUID> found =
                calls.db(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            byUuid != null
                                                    ? "SELECT uuid FROM players WHERE uuid = ?"
                                                    : "SELECT uuid FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1")) {
                                ps.setString(1, byUuid != null ? byUuid.toString() : id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next()
                                            ? Optional.of(UUID.fromString(rs.getString(1)))
                                            : Optional.<UUID>empty();
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

    /**
     * Online check across the network from the session lock ({@code harbor:lock:<uuid>}, held by
     * the server that has the player and switched to handoff while moving). This server's own
     * player list answers first; if Redis cannot be read, only this server is checked (scope =
     * server).
     */
    private static Object online(Calls calls, String id) throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        String here = calls.api().infra().serverId();
        boolean onHere = calls.main(() -> Bukkit.getPlayer(uuid) != null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uuid", uuid.toString());
        if (onHere) {
            out.put("online", true);
            out.put("server", here);
            out.put("moving", false);
            out.put("scope", "network");
            return out;
        }
        Optional<LockState> lock;
        try {
            lock = new SessionLocks(calls.api().redis()).inspect(uuid);
        } catch (RedisException e) {
            out.put("online", false);
            out.put("scope", "server");
            return out;
        }
        LockState state = lock.orElse(null);
        switch (state) {
            case LockState.Owned(String server) -> {
                out.put("online", true);
                out.put("server", server);
                out.put("moving", false);
            }
            case LockState.Handoff(String to, var _) -> {
                out.put("online", true);
                out.put("server", to);
                out.put("moving", true);
            }
            case null -> out.put("online", false);
        }
        out.put("scope", "network");
        return out;
    }

    private static Object ledger(Calls calls, String id, String currencyKey, int limit)
            throws Router.ApiException {
        UUID uuid = resolve(calls, id);
        Currency currency = currencyKey.equals("mulbineul") ? Currency.MULBINEUL : Currency.GOLD;
        List<LedgerRecord> rows =
                calls.await(calls.api().currency().history(uuid, currency, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LedgerRecord r : rows) {
            out.add(ledgerRow(r));
        }
        return out;
    }

    /** LedgerRecord → snake_case row (currency and reason as lowercase keys). */
    static Map<String, Object> ledgerRow(LedgerRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("currency", r.currency().key());
        m.put("delta", r.delta());
        m.put("balance_after", r.balanceAfter());
        m.put("reason", r.reason().toLowerCase(Locale.ROOT));
        String ref = r.refId();
        if (ref != null) {
            m.put("ref_id", ref);
        }
        m.put("created_at", r.createdAt().toString());
        return m;
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
