package wiki.creeper.harbor.economy.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.economy.api.event.MarketUpdatedEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.fishing.api.FishingService;

/**
 * Sell price and market engine (docs 20 7.4). Every hour on the hour, only the one server that
 * acquires the distributed lock computes, writes market_coef and publishes harbor:market. Each
 * server reads the new values from the DB and swaps its table wholesale. On failure the previous
 * values are kept; if there is no update for more than 3 hours, admins are alerted.
 *
 * <p>Expected appearance share uses the Fishing api's expectedShare (falls back to computing from
 * grade and species weights). The number of players staying in a region for the baseline S₀ will
 * switch to per-region records once the World module exists. For now it uses this server's online
 * count (proposed).
 *
 * <p>Also computes the next-hour forecast next_coef (economy line "Market Forecast").
 */
public final class MarketEngine implements PriceService {

    /** harbor:market payload: sends only the timestamp; values are read from the DB. */
    public record MarketTick(Instant hour) {}

    /** Latest market rate for one species. */
    record Stat(Instant hour, double coef, long sales, double s0, OptionalDouble next) {}

    static final Channel<MarketTick> CHANNEL = new Channel<>(Channels.MARKET, MarketTick.class);
    private static final StatKey SELL_PRICE = StatKey.of("sell_price");

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final AdminAlerts alerts;
    private volatile Map<FishId, Stat> stats = Map.of();
    private volatile Instant lastComputedHour = Instant.EPOCH;
    private volatile Instant newestHour = Instant.now();
    private volatile Instant lastStaleAlert = Instant.EPOCH;

    public MarketEngine(HarborApi api, Tasks tasks, Logger log, AdminAlerts alerts) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.alerts = alerts;
    }

    public void start() {
        // Per-server online counts (so S0 is network-wide)
        api.bus()
                .subscribe(
                        ProxyMessages.HEARTBEATS,
                        envelope -> {
                            ProxyMessages.ServerHeartbeat beat = envelope.payload();
                            beats.put(
                                    beat.serverId(),
                                    new Beat(beat.online(), System.currentTimeMillis()));
                        });
        reload();
        api.bus().subscribe(CHANNEL, envelope -> reload());
        // Check every minute whether the hour has passed
        var _ =
                tasks.timer(
                        20 * 60,
                        20 * 60,
                        () -> {
                            if (currentHour().isAfter(lastComputedHour)) {
                                lastComputedHour = currentHour();
                                var _ = tasks.async(this::recompute);
                            }
                            checkStale();
                        });
    }

    EconomyBalance balance() {
        return new EconomyBalance(api.content().snapshot().single(Balance.TYPE));
    }

    static Instant currentHour() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS);
    }

    /** Main thread: alerts admins if stale_h has passed since the last update (once an hour). */
    private void checkStale() {
        Duration age = Duration.between(newestHour, Instant.now());
        if (age.toHours() >= balance().marketStaleHours()
                && Duration.between(lastStaleAlert, Instant.now()).toHours() >= 1) {
            lastStaleAlert = Instant.now();
            log.warn("시세가 {}시간째 갱신되지 않았습니다 (직전 값 유지)", age.toHours());
            alerts.alert("economy.admin.market_stale", String.valueOf(age.toHours()));
        }
    }

    // ------------------------------------------------------------------ PriceService

    @Override
    public double coefficient(FishId fish) {
        Stat stat = stats.get(fish);
        return stat == null ? 1.0 : stat.coef();
    }

    @Override
    public OptionalDouble nextCoefficient(FishId fish) {
        Stat stat = stats.get(fish);
        return stat == null ? OptionalDouble.empty() : stat.next();
    }

    /** Sales volume S over the last 24 hours (as of the last computation). */
    long sales(FishId fish) {
        Stat stat = stats.get(fish);
        return stat == null ? 0 : stat.sales();
    }

    /** Baseline S₀ (as of the last computation, 0 if none). */
    double s0(FishId fish) {
        Stat stat = stats.get(fish);
        return stat == null ? 0 : stat.s0();
    }

    @Override
    public OptionalLong basePrice(HarborItem.Fish fish) {
        return price(fish, 1.0, coefficient(fish.species()));
    }

    @Override
    public OptionalLong quote(UUID player, HarborItem.Fish fish) {
        return price(fish, api.modifiers().value(player, SELL_PRICE), coefficient(fish.species()));
    }

    /** Computes with a set market factor (bulk-sale slippage, bulk-buyer trade M = 1.0). */
    OptionalLong quoteWith(UUID player, HarborItem.Fish fish, double coef) {
        return price(fish, api.modifiers().value(player, SELL_PRICE), coef);
    }

    private OptionalLong price(HarborItem.Fish item, double sellMult, double coef) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, item.species().id());
        if (def.isEmpty() || !def.get().sellable() || def.get().releaseOnly()) {
            return OptionalLong.empty();
        }
        EconomyBalance b = balance();
        double price =
                def.get().basePrice()
                        * b.qualityMult(item.quality())
                        * coef
                        * sellMult
                        * b.regionFactor(def.get().region().id().name());
        long result = (long) Math.floor(price);
        if (def.get().grade() == Grade.LEGENDARY) {
            result = Math.min(result, b.legendCap());
        }
        return OptionalLong.of(Math.max(0, result));
    }

    // ------------------------------------------------------------------ Sales volume

    /**
     * Main thread after a sale commit: adds to that hour's sales. Written as an increment:
     * previously each server overwrote the row with its own absolute total, so with sales on
     * several servers only the last writer's count remained and sales were undercounted (prices
     * fell less, enabling bulk sales).
     */
    void recordSale(FishId fish, int count) {
        if (count <= 0) {
            return;
        }
        Instant hour = currentHour();
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO market_sales (fish_id, hour, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = count + VALUES(count)")) {
                                        ps.setString(1, fish.toString());
                                        ps.setTimestamp(2, Timestamp.from(hour));
                                        ps.setInt(3, count);
                                        ps.executeUpdate();
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "판매량 기록 실패 {} {}: {}",
                                            fish,
                                            count,
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    /** Latest heartbeat online count per server (network-wide online = sum). */
    private record Beat(int online, long atMillis) {}

    private final Map<String, Beat> beats = new ConcurrentHashMap<>();

    /** Heartbeats older than this count as a stopped server (heartbeats come every 5 seconds). */
    private static final long BEAT_STALE_MILLIS = 15_000;

    /**
     * Network-wide online count (for S0). Previously the online count of the single server that won
     * the market lock was used (and off the main thread): when the lobby won the lock it was 0 and
     * the coefficient hit the floor. Falls back to this server's online count without heartbeats.
     */
    private int networkOnline() {
        long now = System.currentTimeMillis();
        int sum = 0;
        boolean any = false;
        for (Beat beat : beats.values()) {
            if (now - beat.atMillis() <= BEAT_STALE_MILLIS) {
                sum += beat.online();
                any = true;
            }
        }
        return any ? sum : api.players().online().size();
    }

    // ------------------------------------------------------------------ Computation

    private static Map<String, Long> sums(
            java.sql.Connection c, String sql, Instant from, Instant to) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return result;
    }

    /** Virtual thread: only the one server that acquires the lock computes. */
    public void recompute() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:market", Duration.ofMinutes(5));
        } catch (RuntimeException e) {
            log.warn("시세 잠금 실패, 직전 값을 유지합니다: {}", String.valueOf(e.getMessage()));
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            EconomyBalance b = balance();
            Instant hour = currentHour();
            Duration window = Duration.ofHours(b.marketWindowHours());
            Instant windowStart = hour.minus(window);
            ContentSnapshot snapshot = api.content().snapshot();
            int online = networkOnline();
            Optional<FishingService> fishing = api.service(FishingService.class);
            api.writeBehind().flush();
            String range =
                    "SELECT fish_id, SUM(count) FROM market_sales WHERE hour >= ? AND hour < ? GROUP BY fish_id";
            api.database()
                    .txVoid(
                            c -> {
                                // S: total within the window (including this hour), leaving: the
                                // hour that drops out
                                // of the window at the next hour mark, lastHour: the past hour
                                Map<String, Long> sales =
                                        sums(c, range, windowStart, hour.plus(Duration.ofHours(1)));
                                Map<String, Long> leaving =
                                        sums(
                                                c,
                                                range,
                                                windowStart,
                                                windowStart.plus(Duration.ofHours(1)));
                                Map<String, Long> lastHour =
                                        sums(c, range, hour.minus(Duration.ofHours(1)), hour);
                                Map<Grade, Double> gradeWeights = new EnumMap<>(Grade.class);
                                for (Grade g : Grade.values()) {
                                    gradeWeights.put(
                                            g,
                                            snapshot.single(Balance.TYPE)
                                                    .number("fishing.grade_weight." + g.key(), 0));
                                }
                                List<FishDef> all =
                                        new ArrayList<>(snapshot.all(FishDef.TYPE).values());
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "INSERT INTO market_coef (fish_id, hour, s, s0, coef, next_coef) VALUES (?, ?, ?, ?, ?, ?)"
                                                        + " ON DUPLICATE KEY UPDATE s = VALUES(s), s0 = VALUES(s0), coef = VALUES(coef), next_coef = VALUES(next_coef)")) {
                                    for (FishDef fish : all) {
                                        if (!fish.sellable()) {
                                            continue;
                                        }
                                        double share =
                                                fishing.map(
                                                                f ->
                                                                        f.expectedShare(
                                                                                fish.region(),
                                                                                fish.id()))
                                                        .orElseGet(
                                                                () ->
                                                                        MarketMath.expectedShare(
                                                                                fish,
                                                                                all.stream()
                                                                                        .filter(
                                                                                                f ->
                                                                                                        f.region()
                                                                                                                .equals(
                                                                                                                        fish
                                                                                                                                .region()))
                                                                                        .toList(),
                                                                                gradeWeights));
                                        double s0 =
                                                Math.max(
                                                        b.s0Floor(),
                                                        online
                                                                * b.catchesPerHour()
                                                                * b.sellRatio()
                                                                * share
                                                                * b.marketWindowHours());
                                        String id = fish.id().toString();
                                        long s = sales.getOrDefault(id, 0L);
                                        ps.setString(1, id);
                                        ps.setTimestamp(2, Timestamp.from(hour));
                                        ps.setLong(3, s);
                                        ps.setDouble(4, s0);
                                        ps.setDouble(5, MarketMath.coefficient(s, s0, b));
                                        ps.setDouble(
                                                6,
                                                MarketMath.forecast(
                                                        s,
                                                        leaving.getOrDefault(id, 0L),
                                                        lastHour.getOrDefault(id, 0L),
                                                        s0,
                                                        b));
                                        ps.addBatch();
                                    }
                                    ps.executeBatch();
                                }
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "DELETE FROM market_sales WHERE hour < ?")) {
                                    ps.setTimestamp(
                                            1, Timestamp.from(hour.minus(Duration.ofDays(7))));
                                    ps.executeUpdate();
                                }
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "DELETE FROM market_coef WHERE hour < ?")) {
                                    ps.setTimestamp(
                                            1, Timestamp.from(hour.minus(Duration.ofDays(7))));
                                    ps.executeUpdate();
                                }
                            });
            api.bus().publish(CHANNEL, new MarketTick(hour));
            reload();
        } catch (SQLException | RuntimeException e) {
            log.error("시세 계산 실패, 직전 값을 유지합니다", e);
        } finally {
            lock.get().release();
        }
    }

    /** Reads the latest market rates from the DB and swaps the table wholesale. */
    void reload() {
        var _ =
                api.database()
                        .async(
                                c -> {
                                    Map<FishId, Stat> next = new HashMap<>();
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT m.fish_id, m.coef, m.s, m.s0, m.next_coef, m.hour FROM market_coef m"
                                                            + " JOIN (SELECT fish_id, MAX(hour) AS hour FROM market_coef GROUP BY fish_id) l"
                                                            + " ON l.fish_id = m.fish_id AND l.hour = m.hour")) {
                                        try (ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                try {
                                                    double nextCoef = rs.getDouble(5);
                                                    boolean hasNext = !rs.wasNull();
                                                    next.put(
                                                            FishId.parse(rs.getString(1)),
                                                            new Stat(
                                                                    rs.getTimestamp(6).toInstant(),
                                                                    rs.getDouble(2),
                                                                    rs.getLong(3),
                                                                    rs.getDouble(4),
                                                                    hasNext
                                                                            ? OptionalDouble.of(
                                                                                    nextCoef)
                                                                            : OptionalDouble
                                                                                    .empty()));
                                                } catch (IllegalArgumentException e) {
                                                    // Ignore malformed rows
                                                }
                                            }
                                        }
                                    }
                                    return next;
                                })
                        .thenAcceptAsync(
                                next -> {
                                    stats = Map.copyOf(next);
                                    next.values().stream()
                                            .map(Stat::hour)
                                            .max(Instant::compareTo)
                                            .ifPresent(h -> newestHour = h);
                                    Bukkit.getPluginManager()
                                            .callEvent(new MarketUpdatedEvent(currentHour()));
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "시세를 읽지 못해 직전 값을 유지합니다: {}",
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    /**
     * Market factors over the last 24 hours (oldest first, economy line "Market Info" rank 2
     * graph). The future completes on the main thread.
     */
    CompletableFuture<List<Double>> history(FishId fish) {
        return api.database()
                .async(
                        c -> {
                            List<Double> values = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT coef FROM market_coef WHERE fish_id = ? ORDER BY hour DESC LIMIT 24")) {
                                ps.setString(1, fish.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        values.add(rs.getDouble(1));
                                    }
                                }
                            }
                            return values.reversed();
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    /**
     * All species factors at a given hour (market board 24-hour comparison, proposed). Empty map if
     * none. The future completes on the main thread.
     */
    CompletableFuture<java.util.Map<String, Double>> coefficientsAt(Instant hour) {
        java.sql.Timestamp at = java.sql.Timestamp.from(hour.truncatedTo(ChronoUnit.HOURS));
        return api.database()
                .async(
                        c -> {
                            java.util.Map<String, Double> values = new java.util.HashMap<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT fish_id, coef FROM market_coef WHERE hour = ?")) {
                                ps.setTimestamp(1, at);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        values.put(rs.getString(1), rs.getDouble(2));
                                    }
                                }
                            }
                            return java.util.Map.copyOf(values);
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    /**
     * Admin: manually pin the market rate (written as this hour's value and announced to all
     * servers).
     */
    public void override(FishId fish, double coef) {
        Instant hour = currentHour();
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO market_coef (fish_id, hour, s, s0, coef) VALUES (?, ?, 0, 0, ?)"
                                                            + " ON DUPLICATE KEY UPDATE coef = VALUES(coef)")) {
                                        ps.setString(1, fish.toString());
                                        ps.setTimestamp(2, Timestamp.from(hour));
                                        ps.setDouble(3, coef);
                                        ps.executeUpdate();
                                    }
                                })
                        .thenRun(() -> api.bus().publish(CHANNEL, new MarketTick(hour)));
    }
}
