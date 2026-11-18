package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 기여도 점수·순위·상위 보상·최소 참여 (docs 20 10.4 역할 · 기여도). 점수 = Σ 가중치 × (항목 ÷ 파티 안 그 항목 최댓값). 역할이 달라도 자기 항목에서
 * 1위면 상위권에 들 수 있다.
 */
public final class ContributionCalc {

    /**
     * 플레이어 한 명의 원자료.
     *
     * @param tension 텐션 안전 구간 유지 시간 (초)
     * @param repair 복구한 내구도 + 보급관 지원량
     * @param evasion 회피 성공 횟수 + 예고 범위 회피 횟수
     * @param activeTicks 활동 시간 (틱)
     */
    public record Stats(
            UUID player,
            double damage,
            double tension,
            double repair,
            double evasion,
            long activeTicks) {}

    /**
     * 계산 결과.
     *
     * @param rank 1부터
     * @param top 상위 20%(올림) 보너스 대상
     * @param eligible 최소 참여를 넘겼는지 (아니면 보상 없음)
     */
    public record Scored(Stats stats, double score, int rank, boolean top, boolean eligible) {}

    private ContributionCalc() {}

    /** 점수 하나 (0~1, 가중치 합이 1일 때). */
    public static double score(Stats s, Stats max, ContributionWeights w) {
        return w.damage() * ratio(s.damage(), max.damage())
                + w.tension() * ratio(s.tension(), max.tension())
                + w.repair() * ratio(s.repair(), max.repair())
                + w.evasion() * ratio(s.evasion(), max.evasion());
    }

    /** 상위 보상 인원 = ceil(인원 × 비율). 인원이 있으면 최소 1명. */
    public static int topCount(int players, double ratio) {
        if (players <= 0 || ratio <= 0) {
            return 0;
        }
        return Math.min(players, Math.max(1, (int) Math.ceil(players * ratio - 1e-9)));
    }

    /**
     * 순위를 매긴다. 점수 내림차순, 같으면 피해 → 텐션 → UUID 순 (결과가 매번 같게).
     *
     * @param fightTicks 전투 시간 (최소 참여의 활동 시간 기준)
     * @param minScoreRatio 점수가 최고점의 이 비율 미만이고
     * @param minActiveRatio 활동 시간이 전투 시간의 이 비율 미만이면 보상 없음
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
