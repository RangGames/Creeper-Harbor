package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Contribution score, ranking, top rewards and minimum participation (docs 20 10.4 roles and
 * contribution). Score = Σ weight × (item ÷ max of that item in the party). Even with different
 * roles, being first in your own item can place you near the top.
 */
public final class ContributionCalc {

    /**
     * Raw data for one player.
     *
     * @param tension time spent holding tension in the safe zone (seconds)
     * @param repair durability restored + quartermaster support amount
     * @param evasion successful dodges + telegraph area dodges
     * @param activeTicks active time (ticks)
     */
    public record Stats(
            UUID player,
            double damage,
            double tension,
            double repair,
            double evasion,
            long activeTicks) {}

    /**
     * Calculation result.
     *
     * @param rank starting at 1
     * @param top eligible for the top 20% (rounded up) bonus
     * @param eligible whether minimum participation was met (no reward otherwise)
     */
    public record Scored(Stats stats, double score, int rank, boolean top, boolean eligible) {}

    private ContributionCalc() {}

    /** One score (0-1 when the weights sum to 1). */
    public static double score(Stats s, Stats max, ContributionWeights w) {
        return w.damage() * ratio(s.damage(), max.damage())
                + w.tension() * ratio(s.tension(), max.tension())
                + w.repair() * ratio(s.repair(), max.repair())
                + w.evasion() * ratio(s.evasion(), max.evasion());
    }

    /** Top reward count = ceil(count × ratio). At least 1 if there are players. */
    public static int topCount(int players, double ratio) {
        if (players <= 0 || ratio <= 0) {
            return 0;
        }
        return Math.min(players, Math.max(1, (int) Math.ceil(players * ratio - 1e-9)));
    }

    /**
     * Ranks players. By score descending, ties broken by damage → tension → UUID (so results are
     * deterministic).
     *
     * @param fightTicks fight duration (baseline for the minimum participation active time)
     * @param minScoreRatio if the score is below this ratio of the top score and
     * @param minActiveRatio active time is below this ratio of the fight duration, no reward
     */
    public static List<Scored> rank(
            List<Stats> all,
            ContributionWeights w,
            double topRatio,
            long fightTicks,
            double minScoreRatio,
            double minActiveRatio) {
        if (all.isEmpty()) {
            return List.of();
        }
        double md = 0;
        double mt = 0;
        double mr = 0;
        double me = 0;
        for (Stats s : all) {
            md = Math.max(md, s.damage());
            mt = Math.max(mt, s.tension());
            mr = Math.max(mr, s.repair());
            me = Math.max(me, s.evasion());
        }
        Stats max = new Stats(new UUID(0, 0), md, mt, mr, me, 0);
        record Row(Stats stats, double score) {}
        List<Row> rows = new ArrayList<>();
        for (Stats s : all) {
            rows.add(new Row(s, score(s, max, w)));
        }
        rows.sort(
                Comparator.comparingDouble(Row::score)
                        .reversed()
                        .thenComparingDouble(r -> -r.stats().damage())
                        .thenComparingDouble(r -> -r.stats().tension())
                        .thenComparing(r -> r.stats().player()));
        double best = rows.getFirst().score();
        List<Scored> eligibleFirst = new ArrayList<>();
        List<Boolean> eligible = new ArrayList<>();
        int eligibleCount = 0;
        for (Row r : rows) {
            boolean lowScore = r.score() < best * minScoreRatio;
            boolean lowActive = r.stats().activeTicks() < fightTicks * minActiveRatio;
            boolean ok = !(lowScore && lowActive) && r.score() > 0;
            eligible.add(ok);
            if (ok) {
                eligibleCount++;
            }
        }
        int top = topCount(eligibleCount, topRatio);
        int given = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean ok = eligible.get(i);
            boolean isTop = ok && given < top;
            if (isTop) {
                given++;
            }
            eligibleFirst.add(new Scored(r.stats(), r.score(), i + 1, isTop, ok));
        }
        return List.copyOf(eligibleFirst);
    }

    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : Math.clamp(value / max, 0.0, 1.0);
    }
}
