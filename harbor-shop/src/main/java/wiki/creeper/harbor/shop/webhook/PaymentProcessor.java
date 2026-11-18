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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CreatorDef;
import wiki.creeper.harbor.shop.content.FestivalDef;
import wiki.creeper.harbor.shop.content.ProductDef;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.donor.DonorGrace;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.PearlTx;

/**
 * 수신함 한 건 처리 (docs 20 12.4 6~7단계, '환불 · 지불 거절', '후원 등급'). 가상 스레드에서 블로킹으로 돈다. 모든 지급은 tx_id 참조 ID
 * 유니크로 한 번만 일어나고, 결제 행(purchases.state)을 잠근 뒤 상태를 보고 처리하므로 같은 웹훅이 여러 번 와도, 완료 → 환불 → 분쟁 순서가 뒤섞여도
 * 결과가 같다 (아직 없는 결제의 환불은 재시도로 미룬다).
 */
public final class PaymentProcessor {

    /** 처리 결과. after는 커밋 뒤 메인 스레드에서 할 일 (캐시·알림). */
    public sealed interface Outcome {
        record Done(List<Runnable> after) implements Outcome {}

        record Held(String reason, List<Runnable> after) implements Outcome {}

        record Retry(String reason) implements Outcome {}
    }

    private static final Duration MONTH = Duration.ofDays(30);

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final Hooks hooks;

    /** 커밋 뒤 다른 매니저에 넘길 일. */
    public interface Hooks {
        void donorChanged(UUID player);

        void festival(FestivalManager.Contribution contribution);

        void cosmeticsChanged(UUID player);
    }

    public PaymentProcessor(ShopRuntime rt, PearlManager pearls, Hooks hooks) {
        this.rt = rt;
        this.pearls = pearls;
        this.hooks = hooks;
    }

    public Outcome process(String body) throws SQLException {
        Optional<WebhookEvent> parsed = WebhookEvent.parse(body);
        if (parsed.isEmpty()) {
            return new Outcome.Held("본문 형식 오류", List.of());
        }
        WebhookEvent event = parsed.get();
        return switch (event.type()) {
            case WebhookEvent.PAYMENT_COMPLETED -> completed(event);
            case WebhookEvent.PAYMENT_REFUNDED -> reverse(event, Reversal.REFUND);
            case WebhookEvent.DISPUTE_OPENED -> reverse(event, Reversal.CHARGEBACK);
            case WebhookEvent.DISPUTE_WON -> disputeWon(event);
            case WebhookEvent.DISPUTE_LOST, WebhookEvent.DISPUTE_CLOSED -> disputeClosed(event);
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

    // ------------------------------------------------------------------ 결제 완료

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

    /** 품목 하나 지급. 이미 처리된 결제면 false. */
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
        String state = purchaseState(c, g.lineTxId()).orElse("RECEIVED");
        if ("HELD".equals(state)) {
            // 관리자가 원인(상품 매핑 등)을 고친 뒤 재처리: 이번 검증을 통과했으므로 지급 내용을 다시 적는다
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
            return false; // 이미 지급·환불된 결제 (재전송)
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
                Optional<ShopRepository.Subscription> existing =
                        ShopRepository.subscription(c, player);
                Instant end =
                        DonorGrace.renewedEnd(
                                existing.map(ShopRepository.Subscription::periodEnd).orElse(null),
                                Instant.now(),
                                MONTH);
                ShopRepository.upsertSubscription(
                        c,
                        new ShopRepository.Subscription(
                                player,
                                product.tier(),
                                payment.recurringRef(),
                                DonorGrace.State.ACTIVE,
                                end));
                donors.add(player);
                // 선단 후원자 월 1회 한정 코스메틱 (소유형 혜택, 해지 후에도 유지)
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
        setState(c, g.lineTxId(), "GRANTED", null);
        ShopRepository.insertNotice(
                c, player, product.kind().name(), g.pearls(), product.id().path());
        return true;
    }

    /** 유료 묶음으로 지급하고, debt가 있으면 새 묶음에서 먼저 갚는다 (docs 20 12.4 '잔액 음수 금지'). */
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
                        lotId(c, player, txId)));
        ShopRepository.addDebt(c, player, -repay);
        if (debt - repay <= 0) {
            unlockIfDebt(c, player);
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

    // ------------------------------------------------------------------ 환불·지불 거절

    private enum Reversal {
        REFUND,
        CHARGEBACK
    }

    /** 결제 행 하나 (tx_id 또는 tx_id#순번). */
    private record Row(
            String txId,
            @Nullable UUID player,
            String kind,
            long pearls,
            String state,
            long reversed,
            long debtAdded) {}

    private Outcome reverse(WebhookEvent event, Reversal type) throws SQLException {
        String tx = event.txId();
        if (tx == null) {
            return new Outcome.Held("거래 ID 없음", List.of());
        }
        List<UUID> donors = new ArrayList<>();
        PearlManager.Committed<Integer> committed =
                pearls.runBlocking(
                        t -> {
                            List<Row> rows = rows(t.connection(), tx);
                            if (rows.isEmpty()) {
                                return -1;
                            }
                            int done = 0;
                            for (Row row : rows) {
                                if (!"GRANTED".equals(row.state()) || row.player() == null) {
                                    continue;
                                }
                                reverseRow(t, row, type, donors);
                                done++;
                            }
                            return done;
                        });
        if (committed.value() < 0) {
            // 완료 웹훅보다 먼저 왔다: 재시도로 미룬다
            return new Outcome.Retry("결제 기록이 아직 없음: " + tx);
        }
        List<Runnable> after = new ArrayList<>();
        after.add(() -> pearls.afterCommit(committed));
        donors.forEach(d -> after.add(() -> hooks.donorChanged(d)));
        rows(tx).forEach(
                        r -> {
                            if (r.player() != null) {
                                UUID player = r.player();
                                after.add(() -> rt.notifyPlayer(player, type.name(), r.reversed()));
                            }
                        });
        if (type == Reversal.CHARGEBACK) {
            after.add(() -> rt.alert("chargeback", tx));
        }
        return new Outcome.Done(after);
    }

    private List<Row> rows(String tx) throws SQLException {
        return rt.api().database().tx(c -> rowsNoLock(c, tx));
    }

    private void reverseRow(PearlTx t, Row row, Reversal type, List<UUID> donors)
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
            Long lot = lotId(c, player, row.txId());
            long remaining = lot == null ? 0 : lotRemaining(c, lot);
            ShopRepository.lockDebt(c, player);
            if (remaining > 0) {
                t.apply(new PearlLedger.Change(player, -remaining, reason, ref, false, null, lot));
                taken = remaining;
            }
        }
        long shortage = Math.max(0, row.pearls() - taken);
        if (shortage > 0) {
            ShopRepository.addDebt(c, player, shortage);
            ShopRepository.setLock(c, player, true, "debt:" + ref);
        }
        if (ProductDef.Kind.SUBSCRIPTION.key().equals(row.kind())) {
            ShopRepository.endSubscription(c, player);
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

    /** 분쟁 승소: 회수분을 돌려주고 debt·잠금을 푼다. */
    private Outcome disputeWon(WebhookEvent event) throws SQLException {
        String tx = event.txId();
        if (tx == null) {
            return new Outcome.Held("거래 ID 없음", List.of());
        }
        PearlManager.Committed<Integer> committed =
                pearls.runBlocking(
                        t -> {
                            Connection c = t.connection();
                            List<Row> rows = rows(c, tx);
                            if (rows.isEmpty()) {
                                return -1;
                            }
                            int done = 0;
                            for (Row row : rows) {
                                if (!"CHARGEBACK".equals(row.state()) || row.player() == null) {
                                    continue;
                                }
                                UUID player = Objects.requireNonNull(row.player());
                                ShopRepository.lockDebt(c, player);
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
                                ShopRepository.addDebt(c, player, -row.debtAdded());
                                if (ShopRepository.wallet(c, player).debt() <= 0) {
                                    unlockIfDebt(c, player);
                                }
                                setState(c, row.txId(), "CHARGEBACK_WON", null);
                                done++;
                            }
                            return done;
                        });
        if (committed.value() < 0) {
            return new Outcome.Retry("결제 기록이 아직 없음: " + tx);
        }
        return new Outcome.Done(List.of(() -> pearls.afterCommit(committed)));
    }

    private Outcome disputeClosed(WebhookEvent event) throws SQLException {
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

    // ------------------------------------------------------------------ 구독

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
                                    Optional<ShopRepository.Subscription> existing =
                                            ShopRepository.subscriptionByRecurring(
                                                    c, r.reference());
                                    Optional<UUID> who =
                                            existing.map(ShopRepository.Subscription::player)
                                                    .or(() -> playerFromBody);
                                    if (who.isEmpty()) {
                                        return Optional.<UUID>empty();
                                    }
                                    Optional<ShopRepository.Subscription> current =
                                            existing.isPresent()
                                                    ? existing
                                                    : ShopRepository.subscription(c, who.get());
                                    DonorTier tier =
                                            product.map(ProductDef::tier)
                                                    .or(
                                                            () ->
                                                                    current.map(
                                                                            ShopRepository
                                                                                            .Subscription
                                                                                    ::tier))
                                                    .orElse(DonorTier.NONE);
                                    if (tier == DonorTier.NONE) {
                                        return Optional.<UUID>empty();
                                    }
                                    Instant previous =
                                            current.map(ShopRepository.Subscription::periodEnd)
                                                    .orElse(null);
                                    Instant next = r.nextPaymentAt();
                                    Instant end;
                                    if (WebhookEvent.RECURRING_ENDED.equals(event.type())) {
                                        // 끝: 남은 기간 + 유예 뒤 정리 작업이 ENDED로 닫는다
                                        end = previous != null ? previous : Instant.now();
                                    } else if (next != null) {
                                        end = next;
                                    } else {
                                        end =
                                                previous != null
                                                        ? previous
                                                        : Instant.now().plus(MONTH);
                                    }
                                    ShopRepository.upsertSubscription(
                                            c,
                                            new ShopRepository.Subscription(
                                                    who.get(), tier, r.reference(), state, end));
                                    return who;
                                });
        if (player.isEmpty()) {
            return new Outcome.Held("구독 대상 플레이어·상품을 알 수 없음: " + r.reference(), List.of());
        }
        UUID who = player.get();
        return new Outcome.Done(List.of(() -> hooks.donorChanged(who)));
    }

    // ------------------------------------------------------------------ SQL

    private static Optional<String> purchaseState(Connection c, String tx) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT state FROM purchases WHERE tx_id = ? FOR UPDATE")) {
            ps.setString(1, tx);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static List<Row> rows(Connection c, String tx) throws SQLException {
        return rows(c, tx, true);
    }

    private static List<Row> rowsNoLock(Connection c, String tx) throws SQLException {
        return rows(c, tx, false);
    }

    private static List<Row> rows(Connection c, String tx, boolean lock) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT tx_id, uuid, COALESCE(kind, ''), pearls, state, reversed_pearls, debt_added"
                                + " FROM purchases WHERE tx_id = ? OR tx_id LIKE ?"
                                + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, tx);
            ps.setString(2, tx + "#%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString(2);
                    rows.add(
                            new Row(
                                    rs.getString(1),
                                    uuid == null ? null : UUID.fromString(uuid),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getLong(6),
                                    rs.getLong(7)));
                }
            }
        }
        return rows;
    }

    private static void setState(Connection c, String tx, String state, @Nullable String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE purchases SET state = ?, detail = COALESCE(?, detail) WHERE tx_id = ?")) {
            ps.setString(1, state);
            ShopRepository.setNullable(ps, 2, detail);
            ps.setString(3, tx);
            ps.executeUpdate();
        }
    }

    private static @Nullable Long lotId(Connection c, UUID player, String sourceRef)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT lot_id FROM pearl_lot WHERE uuid = ? AND source_ref = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, sourceRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long lotRemaining(Connection c, long lot) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT remaining FROM pearl_lot WHERE lot_id = ?")) {
            ps.setLong(1, lot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static void unlockIfDebt(Connection c, UUID player) throws SQLException {
        Optional<String> reason = ShopRepository.lockReason(c, player);
        if (reason.isPresent() && reason.get().startsWith("debt:")) {
            ShopRepository.setLock(c, player, false, null);
        }
    }
}
