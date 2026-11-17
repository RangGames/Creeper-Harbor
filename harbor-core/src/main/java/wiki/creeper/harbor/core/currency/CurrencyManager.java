package wiki.creeper.harbor.core.currency;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.ledger.LedgerWriter;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/** CurrencyService 구현. 원장 쓰기는 가상 스레드, 캐시·이벤트는 커밋 뒤 메인 스레드. */
public final class CurrencyManager implements CurrencyService {

    private final Database db;
    private final LedgerWriter writer;
    private final PlayerManager players;
    private final Tasks tasks;

    public CurrencyManager(
            Database db, SeasonBalanceStore store, PlayerManager players, Tasks tasks) {
        this.db = db;
        this.writer = new LedgerWriter(Map.of(Currency.GOLD, store, Currency.MULBINEUL, store));
        this.players = players;
        this.tasks = tasks;
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
