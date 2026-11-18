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
 * 결제 완료 검증 (docs 20 12.4 6단계, 서버 없이 테스트한다): 통화 KRW, products.yml 매핑, 결제 금액과 상품 가격 일치, 자유 금액은 10원
 * 단위·최소 1,000원, uuid 형식. 하나라도 틀리면 HELD로 두고 관리자에게 알린다. 장바구니에 품목이 여럿이면 tx_id#순번으로 나눈다.
 */
public final class PaymentRules {

    /**
     * 지급할 품목 하나.
     *
     * @param lineTxId purchases.tx_id (품목이 하나면 tx_id, 여럿이면 tx_id#1 …)
     * @param pearls 지급 진주 (구독은 월 진주, 축제는 0)
     */
    public record Grant(
            String lineTxId, ProductDef product, UUID player, long amountKrw, long pearls) {}

    /** 검증 결과. */
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
            // paid_price는 품목 줄 합계로 본다 (Phase 3 착수 때 Tebex 문서로 재확인)
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

    /** 원 단위 정수 (소수부가 0이어야 함). */
    static Optional<Long> won(BigDecimal amount) {
        try {
            return Optional.of(amount.stripTrailingZeros().longValueExact());
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }
}
