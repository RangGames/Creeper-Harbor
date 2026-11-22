package wiki.creeper.harbor.economy.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.account.AccountLinkReader;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.event.TradeFlaggedEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * Fraudulent-trade monitoring (docs 20 7.4, Phase 3). Just before an auction sale, scores the trade
 * with {@link FraudMath} from linked accounts (Common AccountLinkReader: Proxy alt_link, shared IPs
 * excluded), the trade count between the same two people, price relative to the reference price,
 * and trap origin. If the score exceeds the threshold, a trade_flag is written inside the sale
 * transaction; if higher still, the sale proceeds are withheld as HELD in the mailbox. Admins are
 * alerted on all servers.
 */
public final class FraudMonitor {

    /** One trade_flag line (admin command). */
    public record Flag(
            long id,
            String kind,
            UUID buyer,
            UUID seller,
            String refId,
            double score,
            String detail,
            boolean held,
            Instant createdAt) {}

    /** 24-hour total for one reason. */
    public record ReasonSum(long plus, long minus, long count) {}

    /** Result of /harbor econ report. */
    public record Report(
            Map<String, ReasonSum> reasons,
            long earned,
            long spent,
            int openFlags,
            double auctionIndex,
            int indexSamples,
            long heldFees) {

        /**
         * Sink rate = spent ÷ earned (target 80–90%, "7. Balance design" 6.4). NaN if nothing was
         * earned.
         */
        public double destructionRate() {
            return earned <= 0 ? Double.NaN : (double) spent / earned;
        }
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final AdminAlerts alerts;

    public FraudMonitor(HarborApi api, Tasks tasks, Logger log, AdminAlerts alerts) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.alerts = alerts;
    }

    /** Blocking (virtual thread): gathers the observations for this trade. */
    static FraudMath.Signals signals(
            Connection c,
            UUID buyer,
            UUID seller,
            long price,
            long ref,
            boolean trapSource,
            EconomyBalance.FraudRules rules)
            throws SQLException {
        boolean linked;
        try {
            linked = AccountLinkReader.areLinked(c, buyer, seller);
        } catch (SQLException e) {
            // Dev environment without the Proxy table yet: treat as no link
            linked = false;
        }
        int pair = 0;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM auction_sale WHERE ((seller = ? AND buyer = ?) OR (seller = ? AND buyer = ?))"
                                + " AND sold_at >= ?")) {
            ps.setString(1, seller.toString());
            ps.setString(2, buyer.toString());
            ps.setString(3, buyer.toString());
            ps.setString(4, seller.toString());
            ps.setTimestamp(
                    5,
                    Timestamp.from(Instant.now().minus(Duration.ofDays(rules.pairWindowDays()))));
            try (ResultSet rs = ps.executeQuery()) {
                pair = rs.next() ? rs.getInt(1) : 0;
            }
        }
        double ratio = ref <= 0 ? 0 : (double) price / ref;
        return new FraudMath.Signals(linked, pair, ratio, trapSource);
    }

    /** Writes the suspicion record in the same transaction. */
    static void record(
            Connection c,
            FraudMath.Verdict verdict,
            UUID buyer,
            UUID seller,
            String refId,
            String detail,
            boolean held)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO trade_flag (kind, buyer, seller, ref_id, score, detail, held) VALUES (?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE score = VALUES(score), detail = VALUES(detail), held = VALUES(held)")) {
            ps.setString(1, verdict.kind());
            ps.setString(2, buyer.toString());
            ps.setString(3, seller.toString());
            ps.setString(4, refId);
            ps.setDouble(5, verdict.score());
            ps.setString(6, detail.length() > 255 ? detail.substring(0, 255) : detail);
            ps.setBoolean(7, held);
            ps.executeUpdate();
        }
    }

    /** Main thread after commit: event, admin alert, log. */
    void announce(UUID buyer, UUID seller, String refId, FraudMath.Verdict verdict, boolean held) {
        log.warn(
                "부정 거래 의심 {} 구매자 {} 판매자 {} 점수 {} {}{}",
                refId,
                buyer,
                seller,
                verdict.score(),
                verdict.reasons(),
                held ? " (대금 보류)" : "");
        Bukkit.getPluginManager()
                .callEvent(
                        new TradeFlaggedEvent(
                                buyer, seller, refId, verdict.score(), verdict.reasons(), held));
        alerts.alert(
                held ? "economy.admin.flag_held" : "economy.admin.flag",
                refId + " " + String.join(",", verdict.reasons()) + " " + (int) verdict.score());
    }

    // ------------------------------------------------------------------ Admin

    public CompletableFuture<List<Flag>> open(int limit) {
        return api.database()
                .async(
                        c -> {
                            List<Flag> flags = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT id, kind, buyer, seller, ref_id, score, detail, held, created_at FROM trade_flag"
                                                    + " WHERE resolved_by IS NULL ORDER BY id DESC LIMIT ?")) {
                                ps.setInt(1, limit);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        flags.add(
                                                new Flag(
                                                        rs.getLong(1),
                                                        rs.getString(2),
                                                        UUID.fromString(rs.getString(3)),
                                                        UUID.fromString(rs.getString(4)),
                                                        rs.getString(5),
                                                        rs.getDouble(6),
                                                        rs.getString(7),
                                                        rs.getBoolean(8),
                                                        rs.getTimestamp(9).toInstant()));
                                    }
                                }
                            }
                            return flags;
                        })
                .thenApplyAsync(f -> f, tasks.main());
    }

    /**
     * Marks it resolved. Returns that flag's reference ID, or an empty string if missing or already
     * resolved.
     */
    public CompletableFuture<String> resolve(long id, String admin) {
        return api.database()
                .async(
                        c -> {
                            String ref = "";
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT ref_id FROM trade_flag WHERE id = ? AND resolved_by IS NULL FOR UPDATE")) {
                                ps.setLong(1, id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        ref = rs.getString(1);
                                    }
                                }
                            }
                            if (!ref.isEmpty()) {
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "UPDATE trade_flag SET resolved_by = ?, resolved_at = CURRENT_TIMESTAMP(3) WHERE id = ?")) {
                                    ps.setString(
                                            1,
                                            admin.length() > 32 ? admin.substring(0, 32) : admin);
                                    ps.setLong(2, id);
                                    ps.executeUpdate();
                                }
                            }
                            return ref;
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /** The flag's reference ID (for releasing a hold). */
    public CompletableFuture<String> refOf(long id) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT ref_id FROM trade_flag WHERE id = ?")) {
                                ps.setLong(1, id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next() ? rs.getString(1) : "";
                                }
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /**
     * 24-hour metrics (docs 20 7.5 /harbor econ report): gold totals per reason, earned and spent,
     * sink rate, open flag count, auction rare market index (median sale price / reference price
     * for rare and above). The future completes on the main thread.
     */
    public CompletableFuture<Report> report() {
        return api.database()
                .async(
                        c -> {
                            Timestamp since =
                                    Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
                            Map<String, ReasonSum> reasons = new LinkedHashMap<>();
                            long earned = 0;
                            long spent = 0;
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT reason, SUM(CASE WHEN delta > 0 THEN delta ELSE 0 END),"
                                                    + " SUM(CASE WHEN delta < 0 THEN -delta ELSE 0 END), COUNT(*)"
                                                    + " FROM ledger WHERE currency = 'GOLD' AND created_at >= ? GROUP BY reason ORDER BY reason")) {
                                ps.setTimestamp(1, since);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        long plus = rs.getLong(2);
                                        long minus = rs.getLong(3);
                                        reasons.put(
                                                rs.getString(1),
                                                new ReasonSum(plus, minus, rs.getLong(4)));
                                        String reason = rs.getString(1);
                                        // Transfer-only reasons (auction proceeds, mail claims) are
                                        // excluded from earned/spent
                                        if (!reason.equals("AUCTION_BUY")
                                                && !reason.equals("AUCTION_SALE")
                                                && !reason.equals("MAIL_GOLD")) {
                                            earned += plus;
                                            spent += minus;
                                        }
                                    }
                                }
                            }
                            int open = 0;
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM trade_flag WHERE resolved_by IS NULL")) {
                                try (ResultSet rs = ps.executeQuery()) {
                                    open = rs.next() ? rs.getInt(1) : 0;
                                }
                            }
                            List<Double> ratios = new ArrayList<>();
                            long heldFees = 0;
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT s.price, l.ref_price, l.grade, s.held, s.fee FROM auction_sale s"
                                                    + " JOIN auction_listing l ON l.id = s.listing_id WHERE s.sold_at >= ?")) {
                                ps.setTimestamp(1, since);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        String grade = rs.getString(3);
                                        long ref = rs.getLong(2);
                                        if (ref > 0
                                                && (grade.equals("rare")
                                                        || grade.equals("epic")
                                                        || grade.equals("legendary"))) {
                                            ratios.add((double) rs.getLong(1) / ref);
                                        }
                                        if (rs.getBoolean(4)) {
                                            heldFees += rs.getLong(5);
                                        }
                                    }
                                }
                            }
                            // Fees of held sales are not in the ledger as AUCTION_FEE, so add them
                            // to spent
                            return new Report(
                                    reasons,
                                    earned,
                                    spent + heldFees,
                                    open,
                                    FraudMath.index(ratios),
                                    ratios.size(),
                                    heldFees);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }
}
