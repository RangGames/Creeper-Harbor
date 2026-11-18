package wiki.creeper.harbor.shop.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.ProductDef;
import wiki.creeper.harbor.shop.content.ShopSettings;

class WebhookTest {

    private static final String SECRET = "test-secret";
    private static final UUID PLAYER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    private static String body(String id, String type, String tx, String amount, long pkg) {
        return """
                {"id":"%s","type":"%s","date":"2027-07-01T00:00:00+09:00","subject":{
                  "transaction_id":"%s","price_paid":{"amount":%s,"currency":"KRW"},
                  "customer":{"username":{"id":"%s","username":"tester"}},
                  "products":[{"id":%d,"name":"p","quantity":1,"paid_price":{"amount":%s,"currency":"KRW"}}]}}
                """
                .formatted(id, type, tx, amount, PLAYER.toString().replace("-", ""), pkg, amount);
    }

    private static String sign(String body) {
        byte[] mac =
                SignatureVerifier.hmac(
                        SECRET.getBytes(StandardCharsets.UTF_8),
                        SignatureVerifier.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(mac);
    }

    @Test
    void signatureMustMatch() {
        SignatureVerifier verifier = SignatureVerifier.create("tebex_v1", SECRET);
        String b = body("w1", "payment.completed", "tx-1", "5000", 2);
        byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
        assertTrue(verifier.verify(bytes, sign(b)));
        assertFalse(verifier.verify(bytes, sign(b + " ")));
        assertFalse(verifier.verify(bytes, null));
        assertFalse(SignatureVerifier.create("tebex_v1", "").verify(bytes, sign(b)));
        assertFalse(SignatureVerifier.create("unknown", SECRET).verify(bytes, sign(b)));
    }

    @Test
    void sameWebhookHundredTimesConcurrentlyIsStoredOnce() throws Exception {
        Set<String> inbox = ConcurrentHashMap.newKeySet();
        WebhookIntake intake =
                new WebhookIntake(
                        SignatureVerifier.create("tebex_v1", SECRET),
                        row -> inbox.add(row.webhookId()));
        String b = body("w-dup", "payment.completed", "tx-dup", "5000", 2);
        byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
        String sig = sign(b);
        List<Future<WebhookIntake.Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> intake.handle(bytes, sig)));
            }
        }
        int accepted = 0;
        for (Future<WebhookIntake.Response> f : futures) {
            WebhookIntake.Response r = f.get();
            assertEquals(200, r.status());
            if (r.outcome() == WebhookIntake.Outcome.ACCEPTED) {
                accepted++;
            }
        }
        assertEquals(1, accepted);
        assertEquals(Set.of("w-dup"), inbox);
    }

    @Test
    void intakeRejectsTamperedOversizedAndAnswersValidation() {
        WebhookIntake intake =
                new WebhookIntake(SignatureVerifier.create("tebex_v1", SECRET), row -> true);
        String b = body("w2", "payment.completed", "tx-2", "5000", 2);
        assertEquals(403, intake.handle(b.getBytes(StandardCharsets.UTF_8), "00").status());
        byte[] huge = new byte[WebhookIntake.MAX_BODY + 1];
        assertEquals(413, intake.handle(huge, "x").status());
        String validation = "{\"id\":\"val-1\",\"type\":\"validation.webhook\",\"subject\":{}}";
        WebhookIntake.Response r =
                intake.handle(validation.getBytes(StandardCharsets.UTF_8), sign(validation));
        assertEquals(WebhookIntake.Outcome.VALIDATION, r.outcome());
        assertEquals("{\"id\":\"val-1\"}", r.body());
        WebhookIntake failing =
                new WebhookIntake(
                        SignatureVerifier.create("tebex_v1", SECRET),
                        row -> {
                            throw new IllegalStateException("db down");
                        });
        assertEquals(500, failing.handle(b.getBytes(StandardCharsets.UTF_8), sign(b)).status());
    }

    private static final ShopSettings.Topup TOPUP = new ShopSettings.Topup("KRW", 1000, 10);

    private static Optional<ProductDef> product(long id) {
        return switch ((int) id) {
            case 2 ->
                    Optional.of(
                            new ProductDef(
                                    HarborId.parse("product/pearls_500"),
                                    2,
                                    ProductDef.Kind.PEARLS,
                                    500,
                                    DonorTier.NONE,
                                    5000,
                                    null));
            case 5 ->
                    Optional.of(
                            new ProductDef(
                                    HarborId.parse("product/custom"),
                                    5,
                                    ProductDef.Kind.PEARLS_CUSTOM,
                                    0,
                                    DonorTier.NONE,
                                    0,
                                    null));
            case 11 ->
                    Optional.of(
                            new ProductDef(
                                    HarborId.parse("product/fleet"),
                                    11,
                                    ProductDef.Kind.SUBSCRIPTION,
                                    0,
                                    DonorTier.FLEET,
                                    9900,
                                    null));
            default -> Optional.empty();
        };
    }

    private static PaymentRules.Verdict evaluate(String body) {
        WebhookEvent.Payment p = WebhookEvent.parse(body).orElseThrow().payment().orElseThrow();
        return PaymentRules.evaluate(
                p, WebhookTest::product, TOPUP, rank -> rank == 2 ? 700L : 300L);
    }

    @Test
    void paymentValidation() {
        PaymentRules.Verdict ok = evaluate(body("a", "payment.completed", "tx-ok", "5000.00", 2));
        PaymentRules.Grant grant =
                assertInstanceOf(PaymentRules.Verdict.Ok.class, ok).grants().getFirst();
        assertEquals(500, grant.pearls());
        assertEquals(PLAYER, grant.player());
        assertEquals("tx-ok", grant.lineTxId());
        // 금액 불일치 → HELD
        assertInstanceOf(
                PaymentRules.Verdict.Held.class,
                evaluate(body("b", "payment.completed", "tx-b", "4900", 2)));
        // 자유 금액: 10원 단위·최소 1,000원
        PaymentRules.Verdict custom = evaluate(body("c", "payment.completed", "tx-c", "12340", 5));
        assertEquals(1234, ((PaymentRules.Verdict.Ok) custom).grants().getFirst().pearls());
        assertInstanceOf(
                PaymentRules.Verdict.Held.class,
                evaluate(body("d", "payment.completed", "tx-d", "12345", 5)));
        assertInstanceOf(
                PaymentRules.Verdict.Held.class,
                evaluate(body("e", "payment.completed", "tx-e", "990", 5)));
        // 매핑 없는 패키지, 원 단위가 아닌 금액
        assertInstanceOf(
                PaymentRules.Verdict.Held.class,
                evaluate(body("f", "payment.completed", "tx-f", "5000", 99)));
        assertInstanceOf(
                PaymentRules.Verdict.Held.class,
                evaluate(body("g", "payment.completed", "tx-g", "5000.5", 2)));
        // 구독 월 진주
        PaymentRules.Verdict sub = evaluate(body("h", "payment.completed", "tx-h", "9900", 11));
        assertEquals(700, ((PaymentRules.Verdict.Ok) sub).grants().getFirst().pearls());
        // 통화
        String usd = body("i", "payment.completed", "tx-i", "5000", 2).replace("KRW", "USD");
        assertInstanceOf(PaymentRules.Verdict.Held.class, evaluate(usd));
    }

    @Test
    void parsesRecurringAndUuidWithoutDashes() {
        String json =
                """
                {"id":"r1","type":"recurring-payment.started","subject":{"reference":"rec-1",
                 "next_payment_at":"2027-08-01T00:00:00+00:00",
                 "initial_payment":{"transaction_id":"tx-r","price_paid":{"amount":4900,"currency":"KRW"},
                   "customer":{"username":{"id":"%s"}},"products":[{"id":10,"paid_price":{"amount":4900,"currency":"KRW"}}]}}}
                """
                        .formatted(PLAYER.toString().replace("-", ""));
        WebhookEvent.Recurring r = WebhookEvent.parse(json).orElseThrow().recurring().orElseThrow();
        assertEquals("rec-1", r.reference());
        assertEquals(PLAYER, r.player().orElseThrow());
        assertEquals(10, r.packageId());
        assertEquals("2027-08-01T00:00:00Z", String.valueOf(r.nextPaymentAt()));
        assertEquals(Optional.of(5000L), PaymentRules.won(new BigDecimal("5000.00")));
    }
}
