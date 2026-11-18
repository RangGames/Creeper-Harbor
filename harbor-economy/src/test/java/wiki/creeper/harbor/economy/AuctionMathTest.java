package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.service.AuctionMath;

class AuctionMathTest {

    private static final EconomyBalance BALANCE = new EconomyBalance(Balance.of(Map.of()));

    @Test
    void settlementMatchesDocumentExample() {
        // docs 20 7.4: 10,000G 등록 → 등록비 100G, 수수료 500G → 판매자 순수령 9,400G
        long price = 10_000;
        assertEquals(100, AuctionMath.listFee(price, BALANCE.auctionListFee()));
        AuctionMath.Settlement s = AuctionMath.settle(price, BALANCE.auctionSaleFee());
        assertEquals(10_000, s.buyerPays());
        assertEquals(10_000, s.sellerGross());
        assertEquals(500, s.fee());
        assertEquals(9_500, s.sellerNet());
        assertEquals(9_400, s.sellerNet() - AuctionMath.listFee(price, BALANCE.auctionListFee()));
        // 계열 2차 4%, 3차 3%
        assertEquals(400, AuctionMath.settle(price, 0.04).fee());
        assertEquals(300, AuctionMath.settle(price, 0.03).fee());
    }

    @Test
    void goldIsConservedExceptFee() {
        for (long price = 1; price < 5_000; price += 37) {
            AuctionMath.Settlement s = AuctionMath.settle(price, 0.05);
            // 구매자 −가격, 판매자 +가격 −수수료: 사라지는 골드는 수수료뿐
            assertEquals(s.fee(), s.buyerPays() - s.sellerNet());
            assertTrue(s.fee() >= 0 && s.fee() <= price);
        }
    }

    @Test
    void feesRoundSafely() {
        assertEquals(1, AuctionMath.listFee(10, 0.01)); // 0.1G → 올림 1G
        assertEquals(0, AuctionMath.listFee(10, 0)); // 경매 감각 2랭크
        assertEquals(0, AuctionMath.saleFee(19, 0.05)); // 0.95G → 버림
        assertEquals(0.0, AuctionMath.clampRate(-0.01));
        assertEquals(1.0, AuctionMath.clampRate(3));
    }

    @Test
    void mythicBoundsAreThreeToSixtyThousand() {
        // D12: 신화 기준가 6,000G → 3,000 ~ 60,000G
        OptionalLong ref = AuctionMath.fishRef(Grade.MYTHIC, OptionalLong.empty(), 6000);
        AuctionMath.Bounds b =
                AuctionMath.bounds(
                        ref.orElseThrow(), BALANCE.auctionMinRatio(), BALANCE.auctionMaxRatio());
        assertEquals(3_000, b.min());
        assertEquals(60_000, b.max());
        assertTrue(b.contains(3_000));
        assertFalse(b.contains(2_999));
        assertFalse(b.contains(60_001));
    }

    @Test
    void fishRefUsesNpcPriceAndRejectsUnsellable() {
        // 전설: 품질 반영 NPC가 (상한 3,000 포함) → 1,500 ~ 30,000
        AuctionMath.Bounds legend =
                AuctionMath.bounds(
                        AuctionMath.fishRef(Grade.LEGENDARY, OptionalLong.of(3000), 6000)
                                .orElseThrow(),
                        0.5,
                        10);
        assertEquals(1_500, legend.min());
        assertEquals(30_000, legend.max());
        // 일반 어종 7G → 4 ~ 70 (하한 올림)
        AuctionMath.Bounds common = AuctionMath.bounds(7, 0.5, 10);
        assertEquals(4, common.min());
        assertEquals(70, common.max());
        // release_only 등 NPC 가격 없음 → 경매 불가
        assertTrue(AuctionMath.fishRef(Grade.EPIC, OptionalLong.empty(), 6000).isEmpty());
        assertTrue(AuctionMath.fishRef(Grade.COMMON, OptionalLong.of(0), 6000).isEmpty());
    }

    @Test
    void itemRefOrderIsSellBuyAuctionRef() {
        assertEquals(
                50,
                AuctionMath.itemRef(OptionalInt.of(50), OptionalInt.of(80), OptionalLong.of(9))
                        .orElseThrow());
        assertEquals(
                80,
                AuctionMath.itemRef(OptionalInt.empty(), OptionalInt.of(80), OptionalLong.of(9))
                        .orElseThrow());
        assertEquals(
                9,
                AuctionMath.itemRef(OptionalInt.empty(), OptionalInt.empty(), OptionalLong.of(9))
                        .orElseThrow());
        assertTrue(
                AuctionMath.itemRef(OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty())
                        .isEmpty());
    }

    @Test
    void slotsAndNewAccount() {
        assertEquals(5, BALANCE.auctionSlots(1));
        assertEquals(10, BALANCE.auctionSlots(3));
        assertEquals(15, BALANCE.auctionSlots(5));
        assertEquals(9, AuctionMath.slots(5, 4)); // 경매 좌판 2랭크
        assertTrue(AuctionMath.newAccountBlocked(Duration.ofHours(4).plusMinutes(59), 5));
        assertFalse(AuctionMath.newAccountBlocked(Duration.ofHours(5), 5));
    }
}
