package wiki.creeper.harbor.shop.webhook;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CreatorDef;
import wiki.creeper.harbor.shop.content.FestivalDef;
import wiki.creeper.harbor.shop.content.ProductDef;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.data.NoticeRepository;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.data.SubscriptionRepository;
import wiki.creeper.harbor.shop.donor.DonorGrace;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.PearlTx;

/**
 * Processes one inbox entry (docs 20 12.4 steps 6-7, "refunds and chargebacks", "donor tiers").
 * Runs blocking on a virtual thread. Every grant happens exactly once via the unique tx_id
 * reference ID, and processing locks the purchase row (purchases.state) and acts on its state, so
 * the result is the same even if a webhook arrives several times or completed → refunded → disputed
 * arrive out of order (a refund for a payment not yet recorded is deferred to a retry).
 */
public final class PaymentProcessor {

    /**
     * Processing result. {@code after} is work to run on the main thread after commit (cache,
     * notices).
     */
    public sealed interface Outcome {
        record Done(List<Runnable> after) implements Outcome {}

        record Held(String reason, List<Runnable> after) implements Outcome {}

        record Retry(String reason) implements Outcome {}
    }

    private static final Duration MONTH = Duration.ofDays(30);

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final Hooks hooks;
    private final PaymentReversals reversals;

    /** Work handed to other managers after commit. */
    public interface Hooks {
        void donorChanged(UUID player);

        void festival(FestivalManager.Contribution contribution);

        void cosmeticsChanged(UUID player);
    }

    public PaymentProcessor(ShopRuntime rt, PearlManager pearls, Hooks hooks) {
        this.rt = rt;
        this.pearls = pearls;
        this.hooks = hooks;
        this.reversals = new PaymentReversals(rt, pearls, hooks);
    }

    public Outcome process(String body) throws SQLException {
        Optional<WebhookEvent> parsed = WebhookEvent.parse(body);
        if (parsed.isEmpty()) {
            return new Outcome.Held("본문 형식 오류", List.of());
        }
        WebhookEvent event = parsed.get();
        return switch (event.type()) {
            case WebhookEvent.PAYMENT_COMPLETED -> completed(event);
            case WebhookEvent.PAYMENT_REFUNDED ->
                    reversals.reverse(event, PaymentReversals.Reversal.REFUND);
            case WebhookEvent.DISPUTE_OPENED ->
                    reversals.reverse(event, PaymentReversals.Reversal.CHARGEBACK);
            case WebhookEvent.DISPUTE_WON -> reversals.disputeWon(event);
            case WebhookEvent.DISPUTE_LOST, WebhookEvent.DISPUTE_CLOSED ->
                    reversals.disputeClosed(event);
            case WebhookEvent.RECURRING_STARTED, WebhookEvent.RECURRING_RENEWED ->
                    recurring(event, DonorGrace.State.ACTIVE);
            case WebhookEvent.RECURRING_CANCEL_ABORTED -> recurring(event, DonorGrace.State.ACTIVE);
            case WebhookEvent.RECURRING_CANCEL_REQUESTED, WebhookEvent.RECURRING_ENDED ->
                    recurring(event, DonorGrace.State.CANCEL_PENDING);
            default -> {
                rt.log().info("처리하지 않는 웹훅 유형: {}", event.type());
                yield new Outcome.Done(List.of());
            }
        };
    }

    // ------------------------------------------------------------------ payment completed

    private Outcome completed(WebhookEvent event) throws SQLException {
        Optional<WebhookEvent.Payment> payment = event.payment();
        if (payment.isEmpty()) {
            return new Outcome.Held("결제 정보 없음", List.of());
        }
        WebhookEvent.Payment p = payment.get();
        ShopSettings settings = rt.settings();
        PaymentRules.Verdict verdict =
                PaymentRules.evaluate(
                        p,
                        id ->
                                rt.snapshot().all(ProductDef.TYPE).values().stream()
                                        .filter(prod -> prod.tebexPackageId() == id)
                                        .findFirst(),
                        settings.topup(),
                        rank ->
                                settings.perk(
                                                rank == DonorTier.FLEET.rank()
                                                        ? DonorTier.FLEET
                                                        : DonorTier.HARBOR)
                                        .monthlyPearls());
        if (verdict instanceof PaymentRules.Verdict.Held(String reason)) {
            rt.api()
                    .database()
                    .txVoid(
                            c -> {
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "INSERT INTO purchases (tx_id, uuid, amount_krw, state, detail)"
                                                        + " VALUES (?, ?, ?, 'HELD', ?) ON DUPLICATE KEY UPDATE"
                                                        + " detail = IF(state = 'HELD', VALUES(detail), detail)")) {
                                    ps.setString(1, p.txId());
                                    ShopRepository.setNullable(
                                            ps,
                                            2,
                                            p.player() == null ? null : p.player().toString());
                                    ps.setLong(3, PaymentRules.won(p.amount()).orElse(0L));
                                    ps.setString(4, reason);
                                    ps.executeUpdate();
                                }
                            });
            return new Outcome.Held(reason, List.of());
        }
        List<PaymentRules.Grant> grants = ((PaymentRules.Verdict.Ok) verdict).grants();
        List<FestivalManager.Contribution> contributions = new ArrayList<>();
        List<UUID> donors = new ArrayList<>();
        List<UUID> cosmeticPlayers = new ArrayList<>();
        PearlManager.Committed<Boolean> committed =
                pearls.runBlocking(
                        tx -> {
                            boolean any = false;
                            for (PaymentRules.Grant g : grants) {
                                any |=
                                        grant(
                                                tx,
                                                g,
                                                p,
                                                settings,
                                                contributions,
                                                donors,
                                                cosmeticPlayers);
                            }
                            return any;
                        });
        List<Runnable> after = new ArrayList<>();
        after.add(() -> pearls.afterCommit(committed));
        for (PaymentRules.Grant g : grants) {
            after.add(() -> rt.notifyPlayer(g.player(), g.product().kind().name(), g.pearls()));
        }
        donors.forEach(d -> after.add(() -> hooks.donorChanged(d)));
        cosmeticPlayers.forEach(d -> after.add(() -> hooks.cosmeticsChanged(d)));
        contributions.forEach(c -> after.add(() -> hooks.festival(c)));
        return new Outcome.Done(after);
    }

    /** Grants one line item. Returns false if the payment was already processed. */
    private boolean grant(
            PearlTx tx,
            PaymentRules.Grant g,
            WebhookEvent.Payment payment,
            ShopSettings settings,
            List<FestivalManager.Contribution> contributions,
            List<UUID> donors,
            List<UUID> cosmeticPlayers)
            throws SQLException {
        Connection c = tx.connection();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO purchases (tx_id, uuid, product, kind, amount_krw, pearls, state)"
                                + " VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED')")) {
            ps.setString(1, g.lineTxId());
            ps.setString(2, g.player().toString());
            ps.setString(3, g.product().id().toString());
            ps.setString(4, g.product().kind().key());
            ps.setLong(5, g.amountKrw());
            ps.setLong(6, g.pearls());
            ps.executeUpdate();
        }
        String state = PurchaseSql.purchaseState(c, g.lineTxId()).orElse("RECEIVED");
        if ("HELD".equals(state)) {
            // Reprocessed after an admin fixed the cause (product mapping etc.): it passed
            // validation this time, so rewrite the grant details
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE purchases SET uuid = ?, product = ?, kind = ?, amount_krw = ?, pearls = ?,"
                                    + " state = 'RECEIVED' WHERE tx_id = ?")) {
                ps.setString(1, g.player().toString());
                ps.setString(2, g.product().id().toString());
                ps.setString(3, g.product().kind().key());
                ps.setLong(4, g.amountKrw());
                ps.setLong(5, g.pearls());
                ps.setString(6, g.lineTxId());
                ps.executeUpdate();
            }
        } else if (!"RECEIVED".equals(state)) {
            return false; // Payment already granted or refunded (resend)
        }
        if (!g.lineTxId().equals(payment.txId())) {
            // With several line items the held row has only the one tx_id: if a refund or dispute
            // closed it while held, do not grant even when reprocessed
            Optional<String> base = PurchaseSql.purchaseState(c, payment.txId());
            if (base.isPresent() && !"HELD".equals(base.get()) && !"RECEIVED".equals(base.get())) {
                PurchaseSql.setState(c, g.lineTxId(), base.get(), "지급 전 " + base.get());
                return false;
            }
        }
        UUID player = g.player();
        ProductDef product = g.product();
        switch (product.kind()) {
            case PEARLS, PEARLS_CUSTOM ->
                    credit(tx, player, g.pearls(), LedgerReason.TOPUP, g.lineTxId());
            case SUBSCRIPTION -> {
                if (g.pearls() > 0) {
                    credit(tx, player, g.pearls(), LedgerReason.SUBSCRIPTION_MONTHLY, g.lineTxId());
                }
                Optional<SubscriptionRepository.Subscription> existing =
                        SubscriptionRepository.subscription(c, player);
                Instant end =
                        DonorGrace.renewedEnd(
                                existing.map(SubscriptionRepository.Subscription::periodEnd)
                                        .orElse(null),
                                Instant.now(),
                                MONTH);
                SubscriptionRepository.upsertSubscription(
                        c,
                        new SubscriptionRepository.Subscription(
                                player,
                                product.tier(),
                                payment.recurringRef(),
                                DonorGrace.State.ACTIVE,
                                end));
                donors.add(player);
                // Monthly limited cosmetic for Fleet donors (an owned perk, kept after
                // cancellation)
                if (product.tier() == DonorTier.FLEET) {
                    Optional<HarborId> monthly =
                            settings.monthlyCosmetic(
                                    YearMonth.now(rt.api().time().zone()).toString());
                    if (monthly.isPresent()
                            && ShopRepository.insertOwned(
                                    c, player, monthly.get(), "DONOR_MONTHLY", g.lineTxId())) {
                        cosmeticPlayers.add(player);
                    }
                }
            }
            case FESTIVAL -> {
                HarborId festivalId = product.festival();
                Optional<FestivalDef> festival =
                        festivalId == null
                                ? Optional.empty()
                                : rt.snapshot().get(FestivalDef.TYPE, festivalId);
                if (festival.isPresent()) {
                    FestivalManager.Contribution contribution =
                            FestivalManager.contribute(
                                    c, festival.get(), player, g.lineTxId(), g.amountKrw());
                    if (contribution != null) {
                        contributions.add(contribution);
                    }
                }
            }
        }
        creatorPayout(c, g, settings);
        PurchaseSql.setState(c, g.lineTxId(), "GRANTED", null);
        NoticeRepository.insertNotice(
                c, player, product.kind().name(), g.pearls(), product.id().path());
        return true;
    }

    /**
     * Credits as a paid lot, repaying any debt from the new lot first (docs 20 12.4 "no negative
     * balance").
     */
    private void credit(PearlTx tx, UUID player, long pearls, LedgerReason reason, String txId)
            throws SQLException {
        Connection c = tx.connection();
        long debt = ShopRepository.lockDebt(c, player);
        tx.apply(PearlLedger.Change.credit(player, pearls, reason, txId, true));
        if (debt <= 0) {
            return;
        }
        long repay = Math.min(debt, pearls);
        tx.apply(
                new PearlLedger.Change(
                        player,
                        -repay,
                        LedgerReason.CHARGEBACK_REVERSAL,
                        "debt:" + txId,
                        false,
                        null,
                        PurchaseSql.lotId(c, player, txId)));
        ShopRepository.addDebt(c, player, -repay);
        if (debt - repay <= 0) {
            PurchaseSql.unlockIfDebt(c, player);
        }
    }

    private void creatorPayout(Connection c, PaymentRules.Grant g, ShopSettings settings)
            throws SQLException {
        if (!settings.features().creatorCodes()
                || (g.product().kind() != ProductDef.Kind.PEARLS
                        && g.product().kind() != ProductDef.Kind.PEARLS_CUSTOM)) {
            return;
        }
        String code = null;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT code FROM creator_support WHERE uuid = ?")) {
            ps.setString(1, g.player().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    code = rs.getString(1);
                }
            }
        }
        if (code == null) {
            return;
        }
        String found = code;
        Optional<CreatorDef> creator =
                rt.snapshot().all(CreatorDef.TYPE).values().stream()
                        .filter(cr -> cr.code().equals(found))
                        .findFirst();
        if (creator.isEmpty()) {
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO creator_payout (tx_id, code, uuid, amount_krw, share_krw) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, g.lineTxId());
            ps.setString(2, found);
            ps.setString(3, g.player().toString());
            ps.setLong(4, g.amountKrw());
            ps.setLong(5, (long) Math.floor(g.amountKrw() * creator.get().shareRate()));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ subscriptions

    private Outcome recurring(WebhookEvent event, DonorGrace.State state) throws SQLException {
        Optional<WebhookEvent.Recurring> found = event.recurring();
        if (found.isEmpty()) {
            return new Outcome.Held("구독 정보 없음", List.of());
        }
        WebhookEvent.Recurring r = found.get();
        Optional<UUID> playerFromBody = r.player();
        Optional<ProductDef> product =
                rt.snapshot().all(ProductDef.TYPE).values().stream()
                        .filter(p -> p.tebexPackageId() == r.packageId())
                        .filter(p -> p.kind() == ProductDef.Kind.SUBSCRIPTION)
                        .findFirst();
        Optional<UUID> player =
                rt.api()
                        .database()
                        .tx(
                                c -> {
                                    Optional<SubscriptionRepository.Subscription> existing =
                                            SubscriptionRepository.subscriptionByRecurring(
                                                    c, r.reference());
                                    Optional<UUID> who =
                                            existing.map(
                                                            SubscriptionRepository.Subscription
                                                                    ::player)
                                                    .or(() -> playerFromBody);
                                    if (who.isEmpty()) {
                                        return Optional.<UUID>empty();
                                    }
                                    Optional<SubscriptionRepository.Subscription> current =
                                            existing.isPresent()
                                                    ? existing
                                                    : SubscriptionRepository.subscription(
                                                            c, who.get());
                                    DonorTier tier =
                                            product.map(ProductDef::tier)
                                                    .or(
                                                            () ->
                                                                    current.map(
                                                                            SubscriptionRepository
                                                                                            .Subscription
                                                                                    ::tier))
                                                    .orElse(DonorTier.NONE);
                                    if (tier == DonorTier.NONE) {
                                        return Optional.<UUID>empty();
                                    }
                                    Instant previous =
                                            current.map(
                                                            SubscriptionRepository.Subscription
                                                                    ::periodEnd)
                                                    .orElse(null);
                                    Instant next = r.nextPaymentAt();
                                    Instant end;
                                    if (WebhookEvent.RECURRING_ENDED.equals(event.type())) {
                                        // Ended: the cleanup job closes it as ENDED after the
                                        // remaining period plus grace
                                        end = previous != null ? previous : Instant.now();
                                    } else if (next != null) {
                                        end = next;
                                    } else {
                                        end =
                                                previous != null
                                                        ? previous
                                                        : Instant.now().plus(MONTH);
                                    }
                                    SubscriptionRepository.upsertSubscription(
                                            c,
                                            new SubscriptionRepository.Subscription(
                                                    who.get(), tier, r.reference(), state, end));
                                    return who;
                                });
        if (player.isEmpty()) {
            return new Outcome.Held("구독 대상 플레이어·상품을 알 수 없음: " + r.reference(), List.of());
        }
        UUID who = player.get();
        return new Outcome.Done(List.of(() -> hooks.donorChanged(who)));
    }
}
