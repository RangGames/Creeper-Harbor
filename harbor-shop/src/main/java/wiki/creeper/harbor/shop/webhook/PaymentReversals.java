package wiki.creeper.harbor.shop.webhook;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.ProductDef;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.data.SubscriptionRepository;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.PearlTx;
import wiki.creeper.harbor.shop.webhook.PaymentProcessor.Outcome;

/**
 * Refund, chargeback and dispute handling for {@link PaymentProcessor} (docs 20 12.4 "refunds and
 * chargebacks"). Runs blocking on a virtual thread.
 */
final class PaymentReversals {

    enum Reversal {
        REFUND,
        CHARGEBACK
    }

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final PaymentProcessor.Hooks hooks;

    PaymentReversals(ShopRuntime rt, PearlManager pearls, PaymentProcessor.Hooks hooks) {
        this.rt = rt;
        this.pearls = pearls;
        this.hooks = hooks;
    }

    Outcome reverse(WebhookEvent event, Reversal type) throws SQLException {
        String tx = event.txId();
        if (tx == null) {
            return new Outcome.Held("거래 ID 없음", List.of());
        }
        List<UUID> donors = new ArrayList<>();
        List<String> ungranted = new ArrayList<>();
        PearlManager.Committed<Integer> committed =
                pearls.runBlocking(
                        t -> {
                            List<PurchaseSql.Row> rows = PurchaseSql.rows(t.connection(), tx);
                            if (rows.isEmpty()) {
                                return -1;
                            }
                            int done = 0;
                            for (PurchaseSql.Row row : rows) {
                                if ("HELD".equals(row.state()) || "RECEIVED".equals(row.state())) {
                                    // Not granted yet: nothing to claw back; just close the state
                                    // so
                                    // grant() stops even if an admin reprocesses it later
                                    PurchaseSql.setState(
                                            t.connection(),
                                            row.txId(),
                                            type == Reversal.REFUND ? "REFUNDED" : "CHARGEBACK",
                                            "지급 전 " + type.name());
                                    ungranted.add(row.txId());
                                    continue;
                                }
                                if (!"GRANTED".equals(row.state()) || row.player() == null) {
                                    continue;
                                }
                                reverseRow(t, row, type, donors);
                                done++;
                            }
                            return done;
                        });
        if (committed.value() < 0) {
            // Arrived before the completion webhook: defer to a retry
            return new Outcome.Retry("결제 기록이 아직 없음: " + tx);
        }
        List<Runnable> after = new ArrayList<>();
        after.add(() -> pearls.afterCommit(committed));
        donors.forEach(d -> after.add(() -> hooks.donorChanged(d)));
        rows(tx).forEach(
                        r -> {
                            if (r.player() != null && !ungranted.contains(r.txId())) {
                                UUID player = r.player();
                                after.add(() -> rt.notifyPlayer(player, type.name(), r.reversed()));
                            }
                        });
        if (type == Reversal.CHARGEBACK) {
            after.add(() -> rt.alert("chargeback", tx));
        }
        return new Outcome.Done(after);
    }

    private List<PurchaseSql.Row> rows(String tx) throws SQLException {
        return rt.api().database().tx(c -> PurchaseSql.rowsNoLock(c, tx));
    }

    private void reverseRow(PearlTx t, PurchaseSql.Row row, Reversal type, List<UUID> donors)
            throws SQLException {
        Connection c = t.connection();
        UUID player = Objects.requireNonNull(row.player());
        LedgerReason reason =
                type == Reversal.REFUND
                        ? LedgerReason.REFUND_REVERSAL
                        : LedgerReason.CHARGEBACK_REVERSAL;
        String ref = (type == Reversal.REFUND ? "refund:" : "chargeback:") + row.txId();
        long taken = 0;
        if (row.pearls() > 0) {
            Long lot = PurchaseSql.lotId(c, player, row.txId());
            long remaining = lot == null ? 0 : PurchaseSql.lotRemaining(c, lot);
            ShopRepository.lockDebt(c, player);
            if (remaining > 0) {
                t.apply(new PearlLedger.Change(player, -remaining, reason, ref, false, null, lot));
                taken = remaining;
            }
        }
        long shortage = Math.max(0, row.pearls() - taken);
        if (shortage > 0) {
            ShopRepository.addDebt(c, player, shortage);
            // Do not overwrite another lock reason such as an admin lock (unlockIfDebt would lift
            // that lock once the debt is fully repaid)
            Optional<String> existing = ShopRepository.lockReason(c, player);
            if (existing.isEmpty() || existing.get().startsWith("debt:")) {
                ShopRepository.setLock(c, player, true, "debt:" + ref);
            }
        }
        if (ProductDef.Kind.SUBSCRIPTION.key().equals(row.kind())) {
            SubscriptionRepository.endSubscription(c, player);
            donors.add(player);
        }
        if (ProductDef.Kind.FESTIVAL.key().equals(row.kind())) {
            FestivalManager.withdraw(c, row.txId());
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE purchases SET state = ?, reversed_pearls = ?, debt_added = ? WHERE tx_id = ?")) {
            ps.setString(1, type == Reversal.REFUND ? "REFUNDED" : "CHARGEBACK");
            ps.setLong(2, taken);
            ps.setLong(3, shortage);
            ps.setString(4, row.txId());
            ps.executeUpdate();
        }
    }

    /** Dispute won: returns the clawed-back pearls and clears debt and the lock. */
    Outcome disputeWon(WebhookEvent event) throws SQLException {
        String tx = event.txId();
        if (tx == null) {
            return new Outcome.Held("거래 ID 없음", List.of());
        }
        PearlManager.Committed<Integer> committed =
                pearls.runBlocking(
                        t -> {
                            Connection c = t.connection();
                            List<PurchaseSql.Row> rows = PurchaseSql.rows(c, tx);
                            if (rows.isEmpty()) {
                                return -1;
                            }
                            int done = 0;
                            for (PurchaseSql.Row row : rows) {
                                if (!"CHARGEBACK".equals(row.state()) || row.player() == null) {
                                    continue;
                                }
                                UUID player = Objects.requireNonNull(row.player());
                                long debt = ShopRepository.lockDebt(c, player);
                                if (row.reversed() > 0) {
                                    t.apply(
                                            new PearlLedger.Change(
                                                    player,
                                                    row.reversed(),
                                                    LedgerReason.CHARGEBACK_RESTORE,
                                                    "restore:" + row.txId(),
                                                    true,
                                                    "restore:" + row.txId(),
                                                    null));
                                }
                                // debt is a per-player total, so the per-row remainder is unknown:
                                // assume as much of this row's share as possible is still owed
                                // (min(debtAdded, debt)), clear only that, and return the rest,
                                // which top-ups already repaid
                                long remaining = Math.min(row.debtAdded(), Math.max(0, debt));
                                long repaid = row.debtAdded() - remaining;
                                if (repaid > 0) {
                                    t.apply(
                                            new PearlLedger.Change(
                                                    player,
                                                    repaid,
                                                    LedgerReason.CHARGEBACK_RESTORE,
                                                    "repaid:" + row.txId(),
                                                    true,
                                                    "repaid:" + row.txId(),
                                                    null));
                                }
                                ShopRepository.addDebt(c, player, -remaining);
                                if (ShopRepository.wallet(c, player).debt() <= 0) {
                                    PurchaseSql.unlockIfDebt(c, player);
                                }
                                PurchaseSql.setState(c, row.txId(), "CHARGEBACK_WON", null);
                                done++;
                            }
                            return done;
                        });
        if (committed.value() < 0) {
            return new Outcome.Retry("결제 기록이 아직 없음: " + tx);
        }
        return new Outcome.Done(List.of(() -> pearls.afterCommit(committed)));
    }

    Outcome disputeClosed(WebhookEvent event) throws SQLException {
        String tx = event.txId();
        if (tx == null) {
            return new Outcome.Held("거래 ID 없음", List.of());
        }
        rt.api()
                .database()
                .txVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "UPDATE purchases SET detail = ? WHERE (tx_id = ? OR tx_id LIKE ?) AND state = 'CHARGEBACK'")) {
                                ps.setString(1, event.type());
                                ps.setString(2, tx);
                                ps.setString(3, tx + "#%");
                                ps.executeUpdate();
                            }
                        });
        return new Outcome.Done(List.of(() -> rt.alert("dispute_closed", tx + " " + event.type())));
    }
}
