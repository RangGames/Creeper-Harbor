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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
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

/**
 * 판매가와 시세 엔진 (docs 20 7.4). 매시 정각 분산 잠금을 얻은 서버 하나만 계산해 market_coef에 쓰고 harbor:market을 발행한다. 각 서버는
 * DB에서 새 값을 읽어 표를 통째로 바꾼다. 실패하면 직전 값을 유지한다.
 *
 * <p>기준량 S₀의 지역 체류 인원은 World 모듈이 생기면 지역별 기록으로 바꾼다. 지금은 이 서버 접속자 수를 쓴다 (제안).
 */
public final class MarketEngine implements PriceService {

    /** harbor:market 본문: 시각만 보내고 값은 DB에서 읽는다. */
    public record MarketTick(Instant hour) {}

    static final Channel<MarketTick> CHANNEL = new Channel<>(Channels.MARKET, MarketTick.class);
    private static final StatKey SELL_PRICE = StatKey.of("sell_price");

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Map<String, Integer> hourSales = new ConcurrentHashMap<>();
    private volatile Map<FishId, Double> coefficients = Map.of();
    private volatile Instant lastComputedHour = Instant.EPOCH;

    public MarketEngine(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        Instant hour = currentHour();
        var _ =
                api.database()
                        .async(
                                c -> {
                                    Map<String, Integer> counts = new HashMap<>();
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT fish_id, count FROM market_sales WHERE hour = ?")) {
                                        ps.setTimestamp(1, Timestamp.from(hour));
                                        try (ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                counts.put(
                                                        key(rs.getString(1), hour), rs.getInt(2));
                                            }
                                        }
                                    }
                                    return counts;
                                })
                        .thenAccept(hourSales::putAll);
        reload();
        api.bus().subscribe(CHANNEL, envelope -> reload());
        // 1분마다 정각이 지났는지 본다
        var _ =
                tasks.timer(
                        20 * 60,
                        20 * 60,
                        () -> {
                            if (currentHour().isAfter(lastComputedHour)) {
                                lastComputedHour = currentHour();
                                var _ = tasks.async(this::recompute);
                            }
                        });
    }

    EconomyBalance balance() {
        return new EconomyBalance(api.content().snapshot().single(Balance.TYPE));
    }

    static Instant currentHour() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS);
    }

    private static String key(String fish, Instant hour) {
        return fish + "|" + hour.toEpochMilli();
    }

    // ------------------------------------------------------------------ PriceService

    @Override
    public double coefficient(FishId fish) {
        return coefficients.getOrDefault(fish, 1.0);
    }

    @Override
    public OptionalLong basePrice(HarborItem.Fish fish) {
        return price(fish, 1.0);
    }

    @Override
    public OptionalLong quote(UUID player, HarborItem.Fish fish) {
        return price(fish, api.modifiers().value(player, SELL_PRICE));
    }

    private OptionalLong price(HarborItem.Fish item, double sellMult) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, item.species().id());
        if (def.isEmpty() || !def.get().sellable() || def.get().releaseOnly()) {
            return OptionalLong.empty();
        }
        EconomyBalance b = balance();
        double price =
                def.get().basePrice()
                        * b.qualityMult(item.quality())
                        * coefficient(item.species())
                        * sellMult
                        * b.regionFactor(def.get().region().id().name());
        long result = (long) Math.floor(price);
        if (def.get().grade() == Grade.LEGENDARY) {
            result = Math.min(result, b.legendCap());
        }
        return OptionalLong.of(Math.max(0, result));
    }

    // ------------------------------------------------------------------ 판매량

    /** 판매 커밋 후 메인 스레드: 시간 판매량을 올리고 절댓값을 지연 쓰기 큐에 넣는다 (같은 키는 마지막 값만). */
    void recordSale(FishId fish, int count) {
        Instant hour = currentHour();
        String k = key(fish.toString(), hour);
        int total = hourSales.merge(k, count, Integer::sum);
        api.writeBehind()
                .enqueue(
                        "market:" + k,
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT INTO market_sales (fish_id, hour, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = VALUES(count)")) {
                                ps.setString(1, fish.toString());
                                ps.setTimestamp(2, Timestamp.from(hour));
                                ps.setInt(3, total);
                                ps.executeUpdate();
                            }
                        });
        // 지난 시간 키는 메모리에서 정리
        hourSales.keySet().removeIf(existing -> !existing.endsWith("|" + hour.toEpochMilli()));
    }

    // ------------------------------------------------------------------ 계산

    /** 가상 스레드: 잠금을 얻은 서버 하나만 계산한다. */
    public void recompute() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:market", Duration.ofMinutes(5));
        } catch (RuntimeException e) {
            log.warn("시세 잠금 실패, 직전 값을 유지합니다: {}", e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            EconomyBalance b = balance();
            Instant hour = currentHour();
            Instant windowStart = hour.minus(Duration.ofHours(b.marketWindowHours()));
            ContentSnapshot snapshot = api.content().snapshot();
            int online = Bukkit.getOnlinePlayers().size();
            api.writeBehind().flush();
            api.database()
                    .txVoid(
                            c -> {
                                Map<String, Long> sales = new HashMap<>();
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "SELECT fish_id, SUM(count) FROM market_sales WHERE hour >= ? GROUP BY fish_id")) {
                                    ps.setTimestamp(1, Timestamp.from(windowStart));
                                    try (ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            sales.put(rs.getString(1), rs.getLong(2));
                                        }
                                    }
                                }
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
                                                "INSERT INTO market_coef (fish_id, hour, s, s0, coef) VALUES (?, ?, ?, ?, ?)"
                                                        + " ON DUPLICATE KEY UPDATE s = VALUES(s), s0 = VALUES(s0), coef = VALUES(coef)")) {
                                    for (FishDef fish : all) {
                                        if (!fish.sellable()) {
                                            continue;
                                        }
                                        List<FishDef> regionFish =
                                                all.stream()
                                                        .filter(
                                                                f ->
                                                                        f.region()
                                                                                .equals(
                                                                                        fish
                                                                                                .region()))
                                                        .toList();
                                        double share =
                                                MarketMath.expectedShare(
                                                        fish, regionFish, gradeWeights);
                                        double s0 =
                                                Math.max(
                                                        b.s0Floor(),
                                                        online
                                                                * b.catchesPerHour()
                                                                * b.sellRatio()
                                                                * share
                                                                * b.marketWindowHours());
                                        long s = sales.getOrDefault(fish.id().toString(), 0L);
                                        ps.setString(1, fish.id().toString());
                                        ps.setTimestamp(2, Timestamp.from(hour));
                                        ps.setLong(3, s);
                                        ps.setDouble(4, s0);
                                        ps.setDouble(5, MarketMath.coefficient(s, s0, b));
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
                            });
            api.bus().publish(CHANNEL, new MarketTick(hour));
            reload();
        } catch (SQLException e) {
            log.error("시세 계산 실패, 직전 값을 유지합니다", e);
        } finally {
            lock.get().release();
        }
    }

    /** DB에서 최신 시세를 읽어 표를 통째로 바꾼다. */
    void reload() {
        var _ =
                api.database()
                        .async(
                                c -> {
                                    Map<FishId, Double> next = new HashMap<>();
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT m.fish_id, m.coef FROM market_coef m"
                                                            + " JOIN (SELECT fish_id, MAX(hour) AS hour FROM market_coef GROUP BY fish_id) l"
                                                            + " ON l.fish_id = m.fish_id AND l.hour = m.hour")) {
                                        try (ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                try {
                                                    next.put(
                                                            FishId.parse(rs.getString(1)),
                                                            rs.getDouble(2));
                                                } catch (IllegalArgumentException e) {
                                                    // 형식이 틀린 행은 무시
                                                }
                                            }
                                        }
                                    }
                                    return next;
                                })
                        .thenAcceptAsync(
                                next -> {
                                    coefficients = Map.copyOf(next);
                                    Bukkit.getPluginManager()
                                            .callEvent(new MarketUpdatedEvent(currentHour()));
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn("시세를 읽지 못해 직전 값을 유지합니다: {}", e.getMessage());
                                    return null;
                                });
    }

    /** 관리자: 시세 수동 고정 (이번 시간 값으로 쓰고 모든 서버에 알린다). */
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
