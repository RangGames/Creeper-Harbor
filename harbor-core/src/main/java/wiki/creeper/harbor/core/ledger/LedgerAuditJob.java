package wiki.creeper.harbor.core.ledger;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.ledger.LedgerAuditor;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.time.HarborTime;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.currency.SeasonBalanceStore;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Reconciles ledger sums against balances ('16. Plugin design' 4.3). Runs daily in the early
 * morning (04:00, proposed value) on a single server (distributed lock); on mismatch it logs an
 * error and notifies online admins (harbor.admin.alerts).
 */
public final class LedgerAuditJob {

    private static final LocalTime RUN_AT = LocalTime.of(4, 0);
    private static final String LOCK_KEY = "harbor:lock:ledger-audit";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final Database db;
    private final SeasonBalanceStore store;
    private final DistributedLock locks;
    private final HarborTime time;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;

    public LedgerAuditJob(
            Database db,
            SeasonBalanceStore store,
            DistributedLock locks,
            HarborTime time,
            MessageService messages,
            Tasks tasks,
            Logger log) {
        this.db = db;
        this.store = store;
        this.locks = locks;
        this.time = time;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
    }

    public void schedule() {
        long delayTicks = Math.max(20, time.untilNext(RUN_AT).toSeconds() * 20);
        var _ =
                tasks.later(
                        delayTicks,
                        () -> {
                            var _ = tasks.async(this::runOnce);
                            schedule();
                        });
    }

    /** Runs on a virtual thread. Skips if another server is already running it. */
    public void runOnce() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = locks.tryAcquire(LOCK_KEY, LOCK_TTL);
        } catch (RuntimeException e) {
            log.warn("원장 검사 잠금 실패, 이번 검사는 건너뜁니다: {}", e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        int total = 0;
        try {
            for (Currency currency : List.of(Currency.GOLD, Currency.MULBINEUL)) {
                List<LedgerAuditor.Mismatch> mismatches = LedgerAuditor.audit(db, currency, store);
                total += mismatches.size();
                mismatches.forEach(
                        m ->
                                log.error(
                                        "원장 불일치: {} {} 원장 합계 {} / 잔액 {}",
                                        m.player(),
                                        m.currency(),
                                        m.ledgerSum(),
                                        m.balance()));
            }
        } catch (SQLException e) {
            log.error("원장 검사 실패", e);
            return;
        } finally {
            lock.get().release();
        }
        log.info("원장 검사 완료: 불일치 {}건", total);
        if (total > 0) {
            int count = total;
            tasks.main()
                    .execute(
                            () ->
                                    Bukkit.getOnlinePlayers().stream()
                                            .filter(p -> p.hasPermission("harbor.admin.alerts"))
                                            .forEach(
                                                    p ->
                                                            messages.send(
                                                                    p,
                                                                    "admin.ledger_mismatch",
                                                                    Placeholder.unparsed(
                                                                            "count",
                                                                            String.valueOf(
                                                                                    count)))));
        }
    }
}
