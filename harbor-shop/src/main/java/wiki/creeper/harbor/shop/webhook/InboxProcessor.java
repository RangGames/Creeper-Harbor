package wiki.creeper.harbor.shop.webhook;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.data.ShopRepository;

/**
 * Webhook inbox processor (docs 20 12.4 step 5, 'processing failures'). Runs only on the receiving
 * server (config webhook.enabled). Even if two servers enable it, the distributed lock
 * harbor:lock:shop-inbox lets only one process. On failure it sets state=FAILED and retries at 1, 5
 * and 30 minute intervals; after 5 failures it becomes DEAD and admins are alerted. The inbox stays
 * in the DB, so processing resumes after a restart even if the server crashes.
 */
public final class InboxProcessor implements WebhookIntake.InboxStore {

    private static final String LOCK = "harbor:lock:shop-inbox";
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);
    private static final int BATCH = 20;

    private record Pending(String webhookId, String type, String body, int attempts) {}

    private final ShopRuntime rt;
    private final PaymentProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean();

    public InboxProcessor(ShopRuntime rt, PaymentProcessor processor) {
        this.rt = rt;
        this.processor = processor;
    }

    public void start() {
        long period = 20L * rt.settings().inbox().pollSeconds();
        var _ = rt.tasks().timer(period, period, this::signal);
    }

    /** Processes once immediately (right after receipt). */
    public void signal() {
        if (running.compareAndSet(false, true)) {
            var _ =
                    rt.tasks()
                            .async(
                                    () -> {
                                        try {
                                            runOnce();
                                        } finally {
                                            running.set(false);
                                        }
                                    });
        }
    }

    @Override
    public boolean insertIfAbsent(WebhookIntake.InboxRow row) throws SQLException {
        return rt.api()
                .database()
                .tx(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT IGNORE INTO shop_webhook_inbox"
                                                    + " (webhook_id, type, tx_id, body_sha256, body, state) VALUES (?, ?, ?, ?, ?, 'RECEIVED')")) {
                                ps.setString(1, row.webhookId());
                                ps.setString(2, row.type());
                                ShopRepository.setNullable(ps, 3, row.txId());
                                ps.setString(4, row.bodySha256());
                                ps.setString(5, row.body());
                                return ps.executeUpdate() == 1;
                            }
                        });
    }

    /** Processes one batch of pending inbox rows (virtual thread). */
    void runOnce() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = rt.api().locks().tryAcquire(LOCK, LOCK_TTL);
        } catch (RuntimeException e) {
            rt.log().warn("수신함 잠금 실패: {}", String.valueOf(e.getMessage()));
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try (DistributedLock.Handle _ = lock.get()) {
            for (Pending p : pending()) {
                handle(p);
            }
        } catch (SQLException e) {
            rt.log().error("수신함 읽기 실패", e);
        }
    }

    private List<Pending> pending() throws SQLException {
        return rt.api()
                .database()
                .tx(
                        c -> {
                            List<Pending> list = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT webhook_id, type, body, attempts FROM shop_webhook_inbox"
                                                    + " WHERE state IN ('RECEIVED', 'FAILED')"
                                                    + " AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))"
                                                    + " ORDER BY received_at LIMIT ?")) {
                                ps.setInt(1, BATCH);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        list.add(
                                                new Pending(
                                                        rs.getString(1),
                                                        rs.getString(2),
                                                        rs.getString(3),
                                                        rs.getInt(4)));
                                    }
                                }
                            }
                            return list;
                        });
    }

    private void handle(Pending p) {
        PaymentProcessor.Outcome outcome;
        try {
            outcome = processor.process(p.body());
        } catch (Exception e) {
            rt.log().error("웹훅 처리 실패 {} {}", p.webhookId(), p.type(), e);
            outcome = new PaymentProcessor.Outcome.Retry(String.valueOf(e.getMessage()));
        }
        ShopSettings.Inbox cfg = rt.settings().inbox();
        try {
            switch (outcome) {
                case PaymentProcessor.Outcome.Done done -> {
                    mark(p.webhookId(), "DONE", p.attempts(), null, null);
                    runAfter(done.after());
                }
                case PaymentProcessor.Outcome.Held held -> {
                    mark(p.webhookId(), "HELD", p.attempts(), held.reason(), null);
                    runAfter(held.after());
                    rt.alert("held", p.webhookId() + " " + held.reason());
                }
                case PaymentProcessor.Outcome.Retry retry -> {
                    int attempts = p.attempts() + 1;
                    if (attempts >= cfg.maxAttempts()) {
                        mark(p.webhookId(), "DEAD", attempts, retry.reason(), null);
                        rt.alert("dead", p.webhookId() + " " + retry.reason());
                    } else {
                        mark(
                                p.webhookId(),
                                "FAILED",
                                attempts,
                                retry.reason(),
                                Instant.now()
                                        .plus(Duration.ofMinutes(cfg.retryAfterMinutes(attempts))));
                    }
                }
            }
        } catch (SQLException e) {
            rt.log().error("수신함 상태 기록 실패 {}", p.webhookId(), e);
        }
    }

    private void runAfter(List<Runnable> after) {
        if (after.isEmpty()) {
            return;
        }
        rt.tasks()
                .main()
                .execute(
                        () ->
                                after.forEach(
                                        r -> {
                                            try {
                                                r.run();
                                            } catch (RuntimeException e) {
                                                rt.log().error("결제 후처리 오류", e);
                                            }
                                        }));
    }

    private void mark(
            String webhookId,
            String state,
            int attempts,
            @Nullable String error,
            @Nullable Instant next)
            throws SQLException {
        rt.api()
                .database()
                .txVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "UPDATE shop_webhook_inbox SET state = ?, attempts = ?, error = ?,"
                                                    + " next_attempt_at = ?, processed_at = IF(? IN ('DONE', 'HELD'), NOW(3), processed_at)"
                                                    + " WHERE webhook_id = ?")) {
                                ps.setString(1, state);
                                ps.setInt(2, attempts);
                                ShopRepository.setNullable(ps, 3, error);
                                ps.setTimestamp(4, next == null ? null : Timestamp.from(next));
                                ps.setString(5, state);
                                ps.setString(6, webhookId);
                                ps.executeUpdate();
                            }
                        });
    }

    // ------------------------------------------------------------------ admin

    /** Failed and held inbox entries (/harbor shop inbox). */
    public record Row(String webhookId, String type, String state, int attempts, String error) {}

    public List<Row> problems(Connection c) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT webhook_id, type, state, attempts, COALESCE(error, '') FROM shop_webhook_inbox"
                                + " WHERE state IN ('FAILED', 'DEAD', 'HELD') ORDER BY received_at DESC LIMIT 20")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            new Row(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getInt(4),
                                    rs.getString(5)));
                }
            }
        }
        return rows;
    }

    /**
     * Makes failed and DEAD entries retry (HELD is reprocessed by webhookId after fixing the
     * cause).
     */
    public int retry(Connection c, @Nullable String webhookId) throws SQLException {
        String sql =
                webhookId == null
                        ? "UPDATE shop_webhook_inbox SET state = 'FAILED', next_attempt_at = NULL, attempts = 0"
                                + " WHERE state IN ('FAILED', 'DEAD')"
                        : "UPDATE shop_webhook_inbox SET state = 'FAILED', next_attempt_at = NULL, attempts = 0"
                                + " WHERE webhook_id = ? AND state IN ('FAILED', 'DEAD', 'HELD')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (webhookId != null) {
                ps.setString(1, webhookId);
            }
            return ps.executeUpdate();
        }
    }
}
