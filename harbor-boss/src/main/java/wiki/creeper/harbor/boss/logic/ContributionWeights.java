package wiki.creeper.harbor.boss.logic;

/** 기여도 가중치 (docs 20 10.4, 제안값 0.3 · 0.3 · 0.2 · 0.2). 점수 = Σ 가중치 × 항목 ÷ 파티 안 그 항목 최댓값. */
public record ContributionWeights(double damage, double tension, double repair, double evasion) {

    public static final ContributionWeights DEFAULT = new ContributionWeights(0.3, 0.3, 0.2, 0.2);
}
