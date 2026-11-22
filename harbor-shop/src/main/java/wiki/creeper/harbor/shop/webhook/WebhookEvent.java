package wiki.creeper.harbor.shop.webhook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tebex webhook body parsing (tested without a server). Format: {@code {id, type, date, subject}}.
 * Field names follow the Tebex Webhooks docs and are to be rechecked at Phase 3 start.
 *
 * @param id webhook ID (inbox webhook_id, the basis for resend idempotency)
 */
public record WebhookEvent(String id, String type, JsonObject subject) {

    public static final String VALIDATION = "validation.webhook";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String DISPUTE_OPENED = "payment.dispute.opened";
    public static final String DISPUTE_WON = "payment.dispute.won";
    public static final String DISPUTE_LOST = "payment.dispute.lost";
    public static final String DISPUTE_CLOSED = "payment.dispute.closed";
    public static final String RECURRING_STARTED = "recurring-payment.started";
    public static final String RECURRING_RENEWED = "recurring-payment.renewed";
    public static final String RECURRING_CANCEL_REQUESTED =
            "recurring-payment.cancellation.requested";
    public static final String RECURRING_CANCEL_ABORTED = "recurring-payment.cancellation.aborted";
    public static final String RECURRING_ENDED = "recurring-payment.ended";

    /** One payment item. */
    public record Line(long packageId, String name, int quantity, BigDecimal paid) {}

    /**
     * The subject of payment.*.
     *
     * @param amount actual paid amount (price_paid.amount, or price.amount if absent)
     * @param recurringRef recurring payment (subscription) reference (absent for one-off)
     */
    public record Payment(
            String txId,
            String currency,
            BigDecimal amount,
            @Nullable UUID player,
            List<Line> lines,
            @Nullable String recurringRef) {}

    /** The subject of recurring-payment.*. */
    public record Recurring(
            String reference,
            @Nullable Instant nextPaymentAt,
            @Nullable Payment initial,
            @Nullable Payment last) {

        /** Subscription target player (last payment → first payment order). */
        public Optional<UUID> player() {
            if (last != null && last.player() != null) {
                return Optional.of(last.player());
            }
            return initial == null ? Optional.empty() : Optional.ofNullable(initial.player());
        }

        /** Subscription package number. */
        public long packageId() {
            Payment p = last != null ? last : initial;
            return p == null || p.lines().isEmpty() ? 0 : p.lines().getFirst().packageId();
        }
    }

    /** Parses the body. Empty Optional if malformed. */
    public static Optional<WebhookEvent> parse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String id = str(json, "id");
            String type = str(json, "type");
            if (id == null || type == null) {
                return Optional.empty();
            }
            JsonObject subject =
                    json.has("subject") && json.get("subject").isJsonObject()
                            ? json.getAsJsonObject("subject")
                            : new JsonObject();
            return Optional.of(new WebhookEvent(id, type, subject));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Payment transaction ID (payment.*: subject.transaction_id, recurring: last payment). Null if
     * absent.
     */
    public @Nullable String txId() {
        String tx = str(subject, "transaction_id");
        if (tx != null) {
            return tx;
        }
        JsonObject last = obj(subject, "last_payment");
        return last == null ? null : str(last, "transaction_id");
    }

    public Optional<Payment> payment() {
        return payment(subject);
    }

    public Optional<Recurring> recurring() {
        String ref = str(subject, "reference");
        if (ref == null) {
            return Optional.empty();
        }
        JsonObject initial = obj(subject, "initial_payment");
        JsonObject last = obj(subject, "last_payment");
        return Optional.of(
                new Recurring(
                        ref,
                        instant(str(subject, "next_payment_at")),
                        initial == null ? null : payment(initial).orElse(null),
                        last == null ? null : payment(last).orElse(null)));
    }

    static Optional<Payment> payment(JsonObject s) {
        String tx = str(s, "transaction_id");
        if (tx == null) {
            return Optional.empty();
        }
        JsonObject price = obj(s, "price_paid");
        if (price == null) {
            price = obj(s, "price");
        }
        String currency = price == null ? "" : String.valueOf(str(price, "currency"));
        BigDecimal amount = price == null ? BigDecimal.ZERO : decimal(price.get("amount"));
        UUID player = null;
        JsonObject customer = obj(s, "customer");
        if (customer != null) {
            JsonObject username = obj(customer, "username");
            player = username == null ? null : uuid(str(username, "id"));
        }
        List<Line> lines = new ArrayList<>();
        if (s.has("products") && s.get("products").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("products")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject p = e.getAsJsonObject();
                JsonObject paid = obj(p, "paid_price");
                if (paid == null) {
                    paid = obj(p, "base_price");
                }
                long packageId = p.has("id") ? p.get("id").getAsLong() : 0;
                int quantity = p.has("quantity") ? p.get("quantity").getAsInt() : 1;
                lines.add(
                        new Line(
                                packageId,
                                String.valueOf(str(p, "name")),
                                quantity,
                                paid == null ? BigDecimal.ZERO : decimal(paid.get("amount"))));
                if (player == null) {
                    JsonObject username = obj(p, "username");
                    player = username == null ? null : uuid(str(username, "id"));
                }
            }
        }
        return Optional.of(
                new Payment(
                        tx,
                        currency,
                        amount,
                        player,
                        List.copyOf(lines),
                        str(s, "recurring_payment_reference")));
    }

    /** Minecraft UUID (with or without dashes). Null if invalid. */
    static @Nullable UUID uuid(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.matches("[0-9a-fA-F]{32}")) {
            s =
                    s.substring(0, 8)
                            + "-"
                            + s.substring(8, 12)
                            + "-"
                            + s.substring(12, 16)
                            + "-"
                            + s.substring(16, 20)
                            + "-"
                            + s.substring(20);
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal decimal(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(e.getAsString().trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static @Nullable Instant instant(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static @Nullable JsonObject obj(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static @Nullable String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
