package wiki.creeper.harbor.shop.pearl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import wiki.creeper.harbor.common.ledger.LedgerAuditor;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.shop.ShopRuntime;

/**
 * 진주 정합성 검사 (docs 20 12.6: pearl_ledger 합계 = pearl_wallet.balance, 매일 새벽). Common LedgerAuditor로 원장
 * 합계를 맞추고, 묶음(pearl_lot) 남은 양 합계 = 잔액, 유료 묶음 합계 = paid_balance도 함께 본다. 한 서버만
 * (harbor:lock:pearl-audit). 틀리면 관리자에게 알린다.
 */
public final class PearlAudit {

    private static final String LOCK = "harbor:lock:pearl-audit";
    private static final LocalTime RUN_AT = LocalTime.of(4, 30);

    private final ShopRuntime rt;

    public PearlAudit(ShopRuntime rt) {
        this.rt = rt;
    }

    public void schedule() {
        long delay = Math.max(20, rt.api().time().untilNext(RUN_AT).toSeconds() * 20);
        var _ =
                rt.tasks()
                        .later(
                                delay,
                                () -> {
                                    var _ = rt.tasks().async(this::runOnce);
                                    schedule();
                                });
    }

    /** 검사 한 번 (가상 스레드). 불일치 줄 목록. */
    public List<String> runOnce() {
        List<String> problems = new ArrayList<>();
        Optional<DistributedLock.Handle> lock;
        try {
            lock = rt.api().locks().tryAcquire(LOCK, Duration.ofMinutes(30));
        } catch (RuntimeException e) {
            problems.add("잠금 실패: " + e.getMessage());
            return problems;
        }
        if (lock.isEmpty()) {
            problems.add("다른 서버가 검사 중");
            return problems;
        }
        try (DistributedLock.Handle _ = lock.get()) {
            for (LedgerAuditor.Mismatch m :
                    LedgerAuditor.audit(rt.api().database(), Currency.PEARL, rt.ledger().store())) {
                problems.add(m.player() + " 원장 " + m.ledgerSum() + " ≠ 잔액 " + m.balance());
            }
            problems.addAll(
                    rt.api()
                            .database()
                            .tx(
                                    c -> {
                                        List<String> lots = new ArrayList<>();
                                        try (PreparedStatement ps =
                                                        c.prepareStatement(
                                                                "SELECT w.uuid, w.balance, w.paid_balance,"
                                                                        + " COALESCE(SUM(l.remaining), 0),"
                                                                        + " COALESCE(SUM(CASE WHEN l.paid = 1 THEN l.remaining ELSE 0 END), 0)"
                                                                        + " FROM pearl_wallet w LEFT JOIN pearl_lot l ON l.uuid = w.uuid"
                                                                        + " GROUP BY w.uuid, w.balance, w.paid_balance"
                                                                        + " HAVING w.balance <> COALESCE(SUM(l.remaining), 0)"
                                                                        + " OR w.paid_balance <> COALESCE(SUM(CASE WHEN l.paid = 1 THEN l.remaining ELSE 0 END), 0)");
                                                ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                lots.add(
                                                        rs.getString(1)
                                                                + " 잔액 "
                                                                + rs.getLong(2)
                                                                + "/유료 "
                                                                + rs.getLong(3)
                                                                + " ≠ 묶음 "
                                                                + rs.getLong(4)
                                                                + "/유료 "
                                                                + rs.getLong(5));
                                            }
                                        }
                                        return lots;
                                    }));
        } catch (Exception e) {
            problems.add("검사 실패: " + e.getMessage());
            rt.log().error("진주 검사 실패", e);
        }
        if (!problems.isEmpty()) {
            rt.alert("audit", problems.size() + "건: " + problems.getFirst());
        } else {
            rt.log().info("진주 원장 검사 통과");
        }
        return problems;
    }
}
