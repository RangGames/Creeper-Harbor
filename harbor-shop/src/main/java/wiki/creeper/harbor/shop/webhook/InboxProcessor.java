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
 * 웹훅 수신함 처리기 (docs 20 12.4 5단계·'처리 실패'). 수신 서버(config webhook.enabled)에서만 돈다. 혹시 두 서버가 켜도 분산 잠금
 * harbor:lock:shop-inbox로 한 서버만 처리한다. 실패하면 state=FAILED로 두고 1분·5분·30분 간격으로 다시 시도하며, 5번 실패하면 DEAD로
 * 바꾸고 관리자에게 알린다. 수신함이 DB에 남아 있으므로 서버가 죽어도 재시작 뒤 이어서 처리한다.
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

    /** 곧바로 한 번 처리한다 (수신 직후). */
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

    /** 처리 대기 중인 수신함을 한 묶음 처리한다 (가상 스레드). */
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

    // ------------------------------------------------------------------ 관리자

    /** 수신함 실패·보류 건 (/harbor shop inbox). */
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

    /** 실패·DEAD 건을 다시 시도하게 한다 (HELD는 원인을 고친 뒤 webhookId로 지정해 재처리). */
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
