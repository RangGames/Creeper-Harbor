package wiki.creeper.harbor.shop.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.shop.ShopRuntime;

/**
 * 일일 결제 대조 (docs 20 12.4 '일일 대조'). 매일 새벽 Tebex API에서 최근 48시간 결제 목록을 받아 purchases와 비교한다. 빠진 결제는 수신함에
 * 합성 웹훅(webhook_id = reconcile:&lt;tx&gt;)으로 넣어 같은 처리기로 지급하고, 금액이 다른 건은 관리자에게 알린다. 한 서버만
 * (harbor:lock:shop-reconcile). API 응답 필드 이름은 Phase 3 착수 때 확인한다 (txn_id · transaction_id 둘 다 받음).
 */
public final class Reconciler {

    private static final String LOCK = "harbor:lock:shop-reconcile";

    /** 원격 결제 한 건. */
    public record RemotePayment(
            String txId,
            String currency,
            String amount,
            @Nullable String uuid,
            long packageId,
            @Nullable Instant at,
            String status) {}

    private final ShopRuntime rt;
    private final InboxProcessor inbox;
    private final String secret;
    private final String url;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Reconciler(ShopRuntime rt, InboxProcessor inbox, String secret, String url) {
        this.rt = rt;
        this.inbox = inbox;
        this.secret = secret;
        this.url = url;
    }

    public void schedule() {
        java.time.LocalTime at = java.time.LocalTime.of(rt.settings().reconcileHour(), 0);
        long delay = Math.max(20, rt.api().time().untilNext(at).toSeconds() * 20);
        var _ =
                rt.tasks()
                        .later(
                                delay,
                                () -> {
                                    var _ = rt.tasks().async(this::runOnce);
                                    schedule();
                                });
    }

    /** 대조 한 번 (가상 스레드). 결과 요약 문자열. */
    public String runOnce() {
        if (secret.isBlank()) {
            return "Tebex API 비밀키가 없어 대조를 건너뜁니다";
        }
        Optional<DistributedLock.Handle> lock;
        try {
            lock = rt.api().locks().tryAcquire(LOCK, Duration.ofMinutes(10));
        } catch (RuntimeException e) {
            return "잠금 실패: " + e.getMessage();
        }
        if (lock.isEmpty()) {
            return "다른 서버가 대조 중";
        }
        try (DistributedLock.Handle _ = lock.get()) {
            Instant since =
                    Instant.now().minus(Duration.ofHours(rt.settings().reconcileWindowHours()));
            List<RemotePayment> remote = fetch(since);
            int missing = 0;
            int mismatched = 0;
            for (RemotePayment p : remote) {
                if (!"complete".equalsIgnoreCase(p.status())) {
                    continue;
                }
                Optional<Long> local = localAmount(p.txId());
                Optional<Long> amount = PaymentRules.won(new java.math.BigDecimal(p.amount()));
                if (local.isEmpty()) {
                    if (inbox.insertIfAbsent(synthetic(p))) {
                        missing++;
                    }
                } else if (amount.isPresent() && !amount.get().equals(local.get())) {
                    mismatched++;
                    rt.alert(
                            "mismatch",
                            p.txId() + " Tebex " + amount.get() + " ≠ 기록 " + local.get());
                }
            }
            if (missing > 0) {
                inbox.signal();
                rt.alert("reconciled", "누락 결제 " + missing + "건을 수신함에 넣었습니다");
            }
            String summary =
                    "대조: 원격 " + remote.size() + "건, 누락 " + missing + ", 금액 불일치 " + mismatched;
            rt.log().info(summary);
            return summary;
        } catch (Exception e) {
            rt.log().error("결제 대조 실패", e);
            return "대조 실패: " + e.getMessage();
        }
    }

    private Optional<Long> localAmount(String tx) throws java.sql.SQLException {
        return rt.api()
                .database()
                .tx(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT SUM(amount_krw), COUNT(*) FROM purchases WHERE tx_id = ? OR tx_id LIKE ?")) {
                                ps.setString(1, tx);
                                ps.setString(2, tx + "#%");
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next() && rs.getInt(2) > 0) {
                                        return Optional.of(rs.getLong(1));
                                    }
                                }
                            }
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT 1 FROM shop_webhook_inbox WHERE tx_id = ? LIMIT 1")) {
                                ps.setString(1, tx);
                                try (ResultSet rs = ps.executeQuery()) {
                                    // 수신함에 이미 있으면(처리 중) 누락이 아니다
                                    return rs.next() ? Optional.of(-1L) : Optional.<Long>empty();
                                }
                            }
                        })
                .filter(v -> v >= 0)
                .or(() -> Optional.empty());
    }

    /** 원격 결제 → 웹훅 형식 본문 (품목 하나로 본다). */
    static WebhookIntake.InboxRow synthetic(RemotePayment p) {
        JsonObject subject = new JsonObject();
        subject.addProperty("transaction_id", p.txId());
        JsonObject price = new JsonObject();
        price.addProperty("amount", p.amount());
        price.addProperty("currency", p.currency());
        subject.add("price_paid", price);
        JsonObject customer = new JsonObject();
        JsonObject username = new JsonObject();
        username.addProperty("id", p.uuid());
        customer.add("username", username);
        subject.add("customer", customer);
        JsonArray products = new JsonArray();
        JsonObject product = new JsonObject();
        product.addProperty("id", p.packageId());
        product.addProperty("quantity", 1);
        product.add("paid_price", price.deepCopy());
        products.add(product);
        subject.add("products", products);
        JsonObject root = new JsonObject();
        root.addProperty("id", "reconcile:" + p.txId());
        root.addProperty("type", WebhookEvent.PAYMENT_COMPLETED);
        root.add("subject", subject);
        String body = root.toString();
        return new WebhookIntake.InboxRow(
                "reconcile:" + p.txId(),
                WebhookEvent.PAYMENT_COMPLETED,
                p.txId(),
                SignatureVerifier.sha256Hex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                body);
    }

    private List<RemotePayment> fetch(Instant since) throws Exception {
        List<RemotePayment> list = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url + "?paged=1&page=" + page))
                            .header("X-Tebex-Secret", secret)
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Tebex API " + response.statusCode());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray data = json.has("data") ? json.getAsJsonArray("data") : new JsonArray();
            boolean older = false;
            for (JsonElement e : data) {
                RemotePayment p = parse(e.getAsJsonObject());
                if (p.at() != null && p.at().isBefore(since)) {
                    older = true;
                    continue;
                }
                list.add(p);
            }
            if (older || data.isEmpty()) {
                break;
            }
        }
        return list;
    }

    static RemotePayment parse(JsonObject o) {
        String tx = str(o, "txn_id");
        if (tx == null) {
            tx = str(o, "transaction_id");
        }
        if (tx == null) {
            tx = String.valueOf(str(o, "id"));
        }
        String currency = "";
        JsonElement cur = o.get("currency");
        if (cur != null && cur.isJsonObject()) {
            currency = String.valueOf(str(cur.getAsJsonObject(), "iso_4217"));
        } else if (cur != null && cur.isJsonPrimitive()) {
            currency = cur.getAsString();
        }
        String uuid = null;
        JsonElement player = o.get("player");
        if (player != null && player.isJsonObject()) {
            uuid = str(player.getAsJsonObject(), "uuid");
        }
        long packageId = 0;
        JsonElement packages = o.get("packages");
        if (packages != null && packages.isJsonArray() && !packages.getAsJsonArray().isEmpty()) {
            JsonObject first = packages.getAsJsonArray().get(0).getAsJsonObject();
            packageId = first.has("id") ? first.get("id").getAsLong() : 0;
        }
        Instant at = null;
        String date = str(o, "date");
        if (date != null) {
            try {
                at = OffsetDateTime.parse(date).toInstant();
            } catch (RuntimeException e) {
                at = null;
            }
        }
        String amount = str(o, "amount");
        return new RemotePayment(
                tx,
                currency,
                amount == null ? "0" : amount,
                uuid,
                packageId,
                at,
                String.valueOf(str(o, "status")));
    }

    private static @Nullable String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
