package wiki.creeper.harbor.boss.logic;

/**
 * Contribution weights (docs 20 10.4, proposed values 0.3 · 0.3 · 0.2 · 0.2). Score = Σ weight ×
 * item ÷ max of that item in the party.
 */
public record ContributionWeights(double damage, double tension, double repair, double evasion) {

    public static final ContributionWeights DEFAULT = new ContributionWeights(0.3, 0.3, 0.2, 0.2);
}
