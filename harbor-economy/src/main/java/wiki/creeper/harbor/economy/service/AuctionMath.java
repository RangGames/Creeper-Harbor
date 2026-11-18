package wiki.creeper.harbor.economy.service;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.OptionalLong;
import wiki.creeper.harbor.common.model.Grade;

/**
 * 경매 계산 ('7. 밸런스 기획' 6.3·6.5, docs 20 7.4 경매장 규칙). 서버 API 없음, 단위 테스트 대상.
 *
 * <p>계산 예: 10,000G 등록 → 등록비 100G, 낙찰 시 수수료 500G → 판매자 순수령 9,400G (등록비 포함 총비용 6%).
 */
public final class AuctionMath {

    private AuctionMath() {}

    /** 등록가 범위. */
    public record Bounds(long min, long max) {

        public boolean contains(long price) {
            return price >= min && price <= max;
        }
    }

    /**
     * 낙찰 정산. buyerPays = sellerGross = 등록가, sellerNet = 등록가 − 수수료.
     *
     * @param fee 판매 수수료 (AUCTION_FEE로 판매자에게서 차감)
     */
    public record Settlement(long buyerPays, long sellerGross, long fee, long sellerNet) {}

    /** 등록가 범위 = 기준가 × [min_ratio, max_ratio]. 하한은 올림, 상한은 버림, 최소 1G. */
    public static Bounds bounds(long ref, double minRatio, double maxRatio) {
        if (ref <= 0) {
            throw new IllegalArgumentException("기준가는 0보다 커야 합니다: " + ref);
        }
        long min = Math.max(1, (long) Math.ceil(ref * minRatio - 1e-9));
        long max = Math.max(min, (long) Math.floor(ref * maxRatio + 1e-9));
        return new Bounds(min, max);
    }

    /**
     * 어종 기준가: 신화는 고정 기준가(D12, 6,000G), 그 밖은 품질 반영 NPC 판매가(현재 M, 개인 보너스 제외, 전설 상한 포함). NPC 판매가가 없으면
     * (release_only 등) 경매 불가.
     */
    public static OptionalLong fishRef(Grade grade, OptionalLong npcPrice, long mythicRef) {
        if (grade == Grade.MYTHIC) {
            return OptionalLong.of(mythicRef);
        }
        if (npcPrice.isPresent() && npcPrice.getAsLong() > 0) {
            return npcPrice;
        }
        return OptionalLong.empty();
    }

    /** 아이템 기준가: sell_price → buy_price → auction_ref 순 (docs 20 7.4, 제안). */
    public static OptionalLong itemRef(OptionalInt sell, OptionalInt buy, OptionalLong auctionRef) {
        if (sell.isPresent() && sell.getAsInt() > 0) {
            return OptionalLong.of(sell.getAsInt());
        }
        if (buy.isPresent() && buy.getAsInt() > 0) {
            return OptionalLong.of(buy.getAsInt());
        }
        if (auctionRef.isPresent() && auctionRef.getAsLong() > 0) {
            return auctionRef;
        }
        return OptionalLong.empty();
    }

    /** 등록비 = 등록가 × 비율, 올림 (비율이 0이면 0). 환불 없음. */
    public static long listFee(long price, double rate) {
        if (rate <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(price * rate - 1e-9));
    }

    /** 판매 수수료 = 낙찰가 × 비율, 버림. */
    public static long saleFee(long price, double rate) {
        if (rate <= 0) {
            return 0;
        }
        return Math.min(price, (long) Math.floor(price * rate + 1e-9));
    }

    public static Settlement settle(long price, double saleFeeRate) {
        if (price <= 0) {
            throw new IllegalArgumentException("낙찰가는 0보다 커야 합니다: " + price);
        }
        long fee = saleFee(price, saleFeeRate);
        return new Settlement(price, price, fee, price - fee);
    }

    /** 보정 후 비율을 [0, 1]로 자른다 (등록비 1% − 경매 감각 0.5%/랭크, 수수료 5% − 계열 패시브). */
    public static double clampRate(double rate) {
        return Math.clamp(rate, 0.0, 1.0);
    }

    /** 등록 슬롯 = 평판 등급 슬롯 + 경매 좌판 보너스 (+2/랭크). */
    public static int slots(int tierSlots, double bonus) {
        return Math.max(0, tierSlots + (int) Math.floor(bonus + 1e-9));
    }

    /** 신규 계정은 플레이 n시간 전 등록 불가 (구매는 가능). */
    public static boolean newAccountBlocked(Duration playtime, int hours) {
        return playtime.compareTo(Duration.ofHours(hours)) < 0;
    }
}
