package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * 부정 거래 점수 (docs 20 7.4 부정 거래 방지, 수치는 모두 제안값). 서버 API 없음, 단위 테스트 대상.
 *
 * <ul>
 *   <li>linked: 공유 IP가 아닌 IP로 연결된 계정 사이 거래 (Common AccountLinkReader)
 *   <li>pair_repeat: 같은 두 사람 사이 거래가 7일에 3회 이상 (이번 거래 포함)
 *   <li>price_outlier: 기준가의 5배 이상 낙찰
 *   <li>wash: 연결 계정끼리 기준가의 0.6배 이하 거래 (헐값 넘기기)
 *   <li>trap_alt: 연결 계정끼리 통발 어종 거래 ('12. 월드 · 하우징 · 통발' 5장)
 * </ul>
 */
public final class FraudMath {

    private FraudMath() {}

    /**
     * 한 거래의 관측값.
     *
     * @param pairTrades 창 안에서 같은 두 사람 사이 거래 수 (이번 거래 제외)
     * @param priceRatio 낙찰가 ÷ 기준가
     * @param trapSource 통발 수확물인지
     */
    public record Signals(boolean linked, int pairTrades, double priceRatio, boolean trapSource) {}

    /**
     * 판정.
     *
     * @param reasons 걸린 규칙 코드 (trade_flag.kind와 detail에 쓴다)
     * @param flag trade_flag 기록·관리자 알림 대상
     * @param hold 판매 대금 보류 대상
     */
    public record Verdict(double score, List<String> reasons, boolean flag, boolean hold) {

        /** trade_flag.kind: 가장 먼저 걸린 규칙 (없으면 none). */
        public String kind() {
            return reasons.isEmpty() ? "none" : reasons.getFirst();
        }
    }

    public static Verdict evaluate(Signals s, EconomyBalance.FraudRules rules) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        if (s.linked()) {
            score += rules.linkedScore();
            reasons.add("linked");
        }
        if (s.pairTrades() + 1 >= rules.pairCount()) {
            score += rules.pairScore();
            reasons.add("pair_repeat");
        }
        if (s.priceRatio() >= rules.outlierRatio()) {
            score += rules.outlierScore();
            reasons.add("price_outlier");
        }
        if (s.linked() && s.priceRatio() <= rules.washRatio()) {
            score += rules.washScore();
            reasons.add("wash");
        }
        if (s.linked() && s.trapSource()) {
            score += rules.trapAltScore();
            reasons.add("trap_alt");
        }
        return new Verdict(
                score,
                List.copyOf(reasons),
                score >= rules.flagScore(),
                score >= rules.holdScore());
    }

    /** 경매 시세 지수 = 낙찰가/기준가 비율의 중앙값 ('7. 밸런스 기획' 11장: 경매 희귀 중앙가 / NPC가, 목표 0.8~2.0). 표본이 없으면 NaN. */
    public static double index(List<Double> ratios) {
        if (ratios.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = ratios.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
