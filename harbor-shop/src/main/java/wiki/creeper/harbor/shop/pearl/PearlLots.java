package wiki.creeper.harbor.shop.pearl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 진주 묶음(pearl_lot) 차감 순서 (docs 20 12.4 '유료·무료 구분', 서버 없이 테스트한다). 무료 묶음(패스·이벤트) 먼저, 그다음 오래된 유료 묶음부터
 * 쓴다. 사용자가 청약철회할 수 있는 유료분이 오래 남게 하려는 제안이며, 순서는 상점 약관과 화면에 표시한다 (법률 검토 항목).
 *
 * <p>불변식: 한 플레이어의 묶음 남은 양 합계 = pearl_wallet.balance, 유료 묶음 합계 = paid_balance.
 */
public final class PearlLots {

    /** 묶음 하나. order는 만든 순서 (lot_id). */
    public record Lot(long id, boolean paid, long remaining) {}

    /** 묶음에서 뺄 양. */
    public record Take(long lotId, boolean paid, long amount) {}

    private PearlLots() {}

    /**
     * 차감 계획을 만든다.
     *
     * @throws IllegalArgumentException 남은 양 합계가 모자랄 때
     */
    public static List<Take> allocate(List<Lot> lots, long amount) {
        if (amount <= 0) {
            return List.of();
        }
        List<Lot> order = new ArrayList<>(lots);
        order.sort(Comparator.comparing(Lot::paid).thenComparingLong(Lot::id));
        List<Take> takes = new ArrayList<>();
        long left = amount;
        for (Lot lot : order) {
            if (left == 0) {
                break;
            }
            if (lot.remaining() <= 0) {
                continue;
            }
            long take = Math.min(lot.remaining(), left);
            takes.add(new Take(lot.id(), lot.paid(), take));
            left -= take;
        }
        if (left > 0) {
            throw new IllegalArgumentException("묶음 잔량 부족: " + left);
        }
        return takes;
    }

    /** 계획 중 유료 묶음에서 빠지는 양. */
    public static long paidPart(List<Take> takes) {
        return takes.stream().filter(Take::paid).mapToLong(Take::amount).sum();
    }
}
