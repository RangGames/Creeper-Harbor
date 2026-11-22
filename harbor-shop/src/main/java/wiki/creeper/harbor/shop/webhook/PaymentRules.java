package wiki.creeper.harbor.shop.webhook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import wiki.creeper.harbor.shop.content.ProductDef;
import wiki.creeper.harbor.shop.content.ShopSettings;

/**
 * Payment completion validation (docs 20 12.4 step 6, tested without a server): currency KRW,
 * products.yml mapping, paid amount equals product price, free amounts in 10-won units with a
 * 1,000-won minimum, uuid format. If anything fails it is set HELD and admins are alerted. A basket
 * with several items is split into tx_id#index.
 */
public final class PaymentRules {

    /**
     * One item to grant.
     *
     * @param lineTxId purchases.tx_id (tx_id for a single item, tx_id#1 … for several)
     * @param pearls pearls to grant (monthly pearls for subscriptions, 0 for festivals)
     */
    public record Grant(
            String lineTxId, ProductDef product, UUID player, long amountKrw, long pearls) {}

    /** Validation result. */
    public sealed interface Verdict {
        record Ok(List<Grant> grants) implements Verdict {}

        record Held(String reason) implements Verdict {}
    }

    private PaymentRules() {}

    public static Verdict evaluate(
            WebhookEvent.Payment payment,
            LongFunction<Optional<ProductDef>> products,
            ShopSettings.Topup topup,
            LongFunction<Long> monthlyPearls) {
        if (!topup.currency().equalsIgnoreCase(payment.currency())) {
            return new Verdict.Held("통화가 " + topup.currency() + "가 아닙니다: " + payment.currency());
        }
        if (payment.player() == null) {
            return new Verdict.Held("플레이어 UUID가 없거나 형식이 틀렸습니다");
        }
        if (payment.lines().isEmpty()) {
            return new Verdict.Held("품목이 없습니다");
        }
        Optional<Long> total = won(payment.amount());
        if (total.isEmpty() || total.get() <= 0) {
            return new Verdict.Held("결제 금액이 원 단위 양수가 아닙니다: " + payment.amount());
        }
        List<Grant> grants = new ArrayList<>();
        long sum = 0;
        int index = 0;
        for (WebhookEvent.Line line : payment.lines()) {
            index++;
            String lineTx =
                    payment.lines().size() == 1 ? payment.txId() : payment.txId() + "#" + index;
            Optional<ProductDef> mapped = products.apply(line.packageId());
            if (mapped.isEmpty()) {
                return new Verdict.Held("매핑되지 않은 패키지: " + line.packageId());
            }
            ProductDef product = mapped.get();
            Optional<Long> paid = won(line.paid());
            if (paid.isEmpty()) {
                return new Verdict.Held("품목 금액이 원 단위가 아닙니다: " + line.paid());
            }
            int quantity = Math.max(1, line.quantity());
            // paid_price is taken as the item line total (recheck against Tebex docs at Phase 3
            // start)
            long lineTotal = paid.get();
            sum += lineTotal;
            long pearls;
            switch (product.kind()) {
                case PEARLS -> {
                    if (lineTotal != product.priceKrw() * quantity) {
                        return new Verdict.Held(
                                "금액 불일치 "
                                        + product.id()
                                        + ": "
                                        + lineTotal
                                        + "원 ≠ "
                                        + product.priceKrw() * quantity
                                        + "원");
                    }
                    pearls = product.pearls() * quantity;
                }
                case PEARLS_CUSTOM -> {
                    if (lineTotal < topup.minKrw() || lineTotal % topup.unitKrw() != 0) {
                        return new Verdict.Held(
                                "자유 금액은 "
                                        + topup.unitKrw()
                                        + "원 단위·최소 "
                                        + topup.minKrw()
                                        + "원입니다: "
                                        + lineTotal);
                    }
                    pearls = lineTotal / ProductDef.KRW_PER_PEARL;
                }
                case SUBSCRIPTION -> {
                    if (lineTotal != product.priceKrw() * quantity) {
                        return new Verdict.Held(
                                "구독 금액 불일치 " + product.id() + ": " + lineTotal + "원");
                    }
                    pearls = monthlyPearls.apply(product.tier().rank()) * quantity;
                }
                case FESTIVAL -> {
                    if (lineTotal <= 0) {
                        return new Verdict.Held("축제 후원 금액이 없습니다");
                    }
                    pearls = 0;
                }
                default -> throw new IllegalStateException();
            }
            grants.add(new Grant(lineTx, product, payment.player(), lineTotal, pearls));
        }
        if (sum != total.get()) {
            return new Verdict.Held("품목 합계 " + sum + "원 ≠ 결제 금액 " + total.get() + "원");
        }
        return new Verdict.Ok(List.copyOf(grants));
    }

    /** Whole won as an integer (fractional part must be 0). */
    static Optional<Long> won(BigDecimal amount) {
        try {
            return Optional.of(amount.stripTrailingZeros().longValueExact());
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }
}
