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
 * 원장 합계와 잔액 대조 ('16. 플러그인 설계' 4.3). 매일 새벽(04:00, 제안값) 한 서버만 실행하고 (분산 잠금), 틀리면 오류 로그를 남기고
 * 접속 중인 관리자(harbor.admin.alerts)에게 알린다.
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
            Database db, SeasonBalanceStore store, DistributedLock locks, HarborTime time, MessageService messages, Tasks tasks, Logger log) {
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
        var unused = tasks.later(delayTicks, () -> {
            var unused2 = tasks.async(this::runOnce);
            schedule();
        });
    }

    /** 가상 스레드에서 실행한다. 다른 서버가 이미 하고 있으면 건너뛴다. */
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
                mismatches.forEach(m -> log.error("원장 불일치: {} {} 원장 합계 {} / 잔액 {}",
                        m.player(), m.currency(), m.ledgerSum(), m.balance()));
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
            tasks.main().execute(() -> Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("harbor.admin.alerts"))
                    .forEach(p -> messages.send(p, "admin.ledger_mismatch", Placeholder.unparsed("count", String.valueOf(count)))));
        }
    }
}
