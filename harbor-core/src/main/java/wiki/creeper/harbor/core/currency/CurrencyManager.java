package wiki.creeper.harbor.core.currency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.ledger.LedgerWriter;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.BalanceRank;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/** CurrencyService 구현. 원장 쓰기는 가상 스레드, 캐시·이벤트는 커밋 뒤 메인 스레드. */
public final class CurrencyManager implements CurrencyService {

    /** 다른 서버가 바꾼 잔액을 이 서버 캐시에 반영하는 알림 (추가 제안). */
    static final Channel<BalanceNotice> BALANCE =
            new Channel<>("harbor:balance", BalanceNotice.class);

    /** 원장 커밋 뒤 잔액 (서버 이동 중이거나 다른 서버에 접속한 플레이어의 캐시용). */
    record BalanceNotice(String player, String currency, long balance) {}

    private static final Duration TOP_TTL = Duration.ofSeconds(30);

    private final Database db;
    private final LedgerWriter writer;
    private final PlayerManager players;
    private final Tasks tasks;
    private final SeasonId season;
    private final Map<Currency, TopCache> topCache = new ConcurrentHashMap<>();
    private @Nullable MessageBus bus;

    private record TopCache(Instant at, List<BalanceRank> rows) {}

    public CurrencyManager(
            Database db,
            SeasonBalanceStore store,
            PlayerManager players,
            Tasks tasks,
            SeasonId season) {
        this.db = db;
        this.writer = new LedgerWriter(Map.of(Currency.GOLD, store, Currency.MULBINEUL, store));
        this.players = players;
        this.tasks = tasks;
        this.season = season;
    }

    /** 다른 서버의 잔액 알림을 받는다. 이 서버에 접속 중인 플레이어면 캐시만 바꾼다 (이벤트는 커밋한 서버에서 이미 발생). */
    public void listen(MessageBus messageBus, String serverId) {
        this.bus = messageBus;
        messageBus.subscribe(
                BALANCE,
                envelope -> {
                    if (serverId.equals(envelope.server())) {
                        return;
                    }
                    BalanceNotice n = envelope.payload();
                    tasks.main()
                            .execute(
                                    () -> {
                                        try {
                                            players.updateCachedBalance(
                                                    UUID.fromString(n.player()),
                                                    Currency.valueOf(n.currency()),
                                                    n.balance());
                                        } catch (IllegalArgumentException e) {
                                            // 형식이 틀린 알림은 버린다
                                        }
                                    });
                });
    }

    public LedgerWriter writer() {
        return writer;
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> give(
            UUID player,
            Currency currency,
            long amount,
            LedgerReason reason,
            @Nullable String refId) {
        requirePositive(amount);
        return post(new LedgerEntry(player, currency, amount, reason, refId));
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> take(
            UUID player,
            Currency currency,
            long amount,
            LedgerReason reason,
            @Nullable String refId) {
        requirePositive(amount);
        return post(new LedgerEntry(player, currency, -amount, reason, refId));
    }

    @Override
    public CompletableFuture<Result<List<Long>, LedgerError>> atomic(
            List<LedgerEntry> entries, @Nullable SqlConsumer extra) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("원장 기록이 없습니다");
        }
        for (LedgerEntry entry : entries) {
            requireGameCurrency(entry.currency());
        }
        List<LedgerEntry> copy = List.copyOf(entries);
        return tasks.supplyAsync(() -> writer.postAll(db, copy, extra))
                .thenApplyAsync(
                        result -> {
                            if (result
                                    instanceof
                                    Result.Ok<List<Long>, LedgerError>(List<Long> balances)) {
                                for (int i = 0; i < copy.size(); i++) {
                                    committed(copy.get(i), balances.get(i));
                                }
                            }
                            return result;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<Result<List<Long>, LedgerError>> transfer(
            UUID from,
            UUID to,
            Currency currency,
            long amount,
            LedgerReason debitReason,
            LedgerReason creditReason,
            String refId) {
        requirePositive(amount);
        return atomic(
                List.of(
                        new LedgerEntry(from, currency, -amount, debitReason, refId),
                        new LedgerEntry(to, currency, amount, creditReason, refId)),
                null);
    }

    @Override
    public CompletableFuture<Long> balance(UUID player, Currency currency) {
        requireGameCurrency(currency);
        Optional<HarborPlayer> online = players.get(player);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(online.get().balance(currency));
        }
        return db.async(c -> dbBalance(c, player, currency)).thenApplyAsync(b -> b, tasks.main());
    }

    @Override
    public CompletableFuture<List<BalanceRank>> top(Currency currency, int offset, int limit) {
        requireGameCurrency(currency);
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset ≥ 0, limit 1~100: " + offset + ", " + limit);
        }
        if (offset + limit <= TOP_CACHED) {
            TopCache cached = topCache.get(currency);
            CompletableFuture<List<BalanceRank>> rows =
                    cached != null && cached.at().plus(TOP_TTL).isAfter(Instant.now())
                            ? CompletableFuture.completedFuture(cached.rows())
                            : db.async(c -> queryTop(c, currency, 0, TOP_CACHED))
                                    .thenApply(
                                            list -> {
                                                topCache.put(
                                                        currency,
                                                        new TopCache(Instant.now(), list));
                                                return list;
                                            });
            return rows.thenApplyAsync(
                    list ->
                            list.subList(
                                    Math.min(offset, list.size()),
                                    Math.min(offset + limit, list.size())),
                    tasks.main());
        }
        return db.async(c -> queryTop(c, currency, offset, limit))
                .thenApplyAsync(list -> list, tasks.main());
    }

    @Override
    public CompletableFuture<Optional<Integer>> rank(UUID player, Currency currency) {
        requireGameCurrency(currency);
        String col = column(currency);
        return db.async(
                        c -> {
                            long mine = dbBalance(c, player, currency);
                            if (mine <= 0) {
                                return Optional.<Integer>empty();
                            }
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM player_season WHERE season = ? AND "
                                                    + col
                                                    + " > ?")) {
                                ps.setInt(1, season.value());
                                ps.setLong(2, mine);
                                try (ResultSet rs = ps.executeQuery()) {
                                    return Optional.of(rs.next() ? rs.getInt(1) + 1 : 1);
                                }
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> set(
            UUID player, Currency currency, long amount, @Nullable String refId) {
        requireGameCurrency(currency);
        if (amount < 0) {
            throw new IllegalArgumentException("잔액은 0 이상이어야 합니다: " + amount);
        }
        return db.async(c -> dbBalance(c, player, currency))
                .thenComposeAsync(
                        now -> {
                            long diff = amount - now;
                            if (diff == 0) {
                                return CompletableFuture.completedFuture(
                                        Result.<Long, LedgerError>ok(now));
                            }
                            return diff > 0
                                    ? give(player, currency, diff, LedgerReason.ADMIN_GRANT, refId)
                                    : take(player, currency, -diff, LedgerReason.ADMIN_TAKE, refId);
                        },
                        tasks.main());
    }

    private long dbBalance(Connection c, UUID player, Currency currency) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + column(currency)
                                + " FROM player_season WHERE uuid = ? AND season = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private List<BalanceRank> queryTop(Connection c, Currency currency, int offset, int limit)
            throws SQLException {
        String col = "s." + column(currency).replace("mulbineul_spent", "s.mulbineul_spent");
        List<BalanceRank> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT s.uuid, p.name, "
                                + col
                                + " AS bal FROM player_season s JOIN players p ON p.uuid = s.uuid"
                                + " WHERE s.season = ? AND "
                                + col
                                + " > 0 ORDER BY bal DESC, p.name LIMIT ? OFFSET ?")) {
            ps.setInt(1, season.value());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = offset;
                while (rs.next()) {
                    rows.add(
                            new BalanceRank(
                                    ++rank,
                                    new PlayerRef(
                                            UUID.fromString(rs.getString(1)), rs.getString(2)),
                                    rs.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** player_season 잔액 식 (물비늘 = 획득 − 사용). */
    private static String column(Currency currency) {
        return currency == Currency.GOLD ? "gold" : "mulbineul_earned - mulbineul_spent";
    }

    private CompletableFuture<Result<Long, LedgerError>> post(LedgerEntry entry) {
        requireGameCurrency(entry.currency());
        return tasks.supplyAsync(() -> writer.post(db, entry))
                .thenApplyAsync(
                        result -> {
                            if (result instanceof Result.Ok<Long, LedgerError>(Long balance)) {
                                committed(entry, balance);
                            }
                            return result;
                        },
                        tasks.main());
    }

    /** 메인 스레드: 커밋된 변경을 캐시에 반영하고 이벤트를 발생시킨다. */
    private void committed(LedgerEntry entry, long balance) {
        players.updateCachedBalance(entry.player(), entry.currency(), balance);
        MessageBus b = bus;
        if (b != null && players.get(entry.player()).isEmpty()) {
            // 다른 서버에 접속 중일 수 있다: 그 서버 캐시도 맞춘다
            b.publish(
                    BALANCE,
                    new BalanceNotice(entry.player().toString(), entry.currency().name(), balance));
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new CurrencyChangedEvent(
                                entry.player(),
                                entry.currency(),
                                entry.delta(),
                                balance,
                                entry.reason()));
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다: " + amount);
        }
    }

    private static void requireGameCurrency(Currency currency) {
        if (!currency.isGameCurrency()) {
            throw new IllegalArgumentException("진주는 Shop 모듈이 관리합니다");
        }
    }
}
