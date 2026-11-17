package wiki.creeper.harbor.fishing.roll;

import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;

/**
 * 크기·품질 판정 ('7. 밸런스 기획' 4.5, docs 20 3.4 결과 처리 1~3단계).
 *
 * <p>X = 균등(0, 1), X' = X^(1 / (1 + 행운)), 크기 = 최소 + (최대 − 최소) × X'. 품질 경계 0.40·0.70·0.90·0.98. 직업
 * 상한보다 높으면 상한 품질의 구간 최댓값으로 고정한다 ('놓친 대어').
 */
public final class QualityRoller {

    /** 결과. capped면 직업 상한에 걸린 것. */
    public record Rolled(double sizeCm, Quality quality, boolean capped) {}

    private QualityRoller() {}

    public static Rolled roll(
            FishDef fish, double luck, Quality cap, double[] bounds, RandomGenerator random) {
        double x = Math.pow(random.nextDouble(), 1 / (1 + Math.max(0, luck)));
        int stars = starsFor(x, bounds);
        boolean capped = stars > cap.stars();
        if (capped) {
            stars = cap.stars();
            // 상한 품질 구간의 최댓값 바로 아래 (★3이면 0.90 바로 아래)
            x = Math.nextDown(bounds[stars - 1]);
        }
        double size = fish.sizeMin() + (fish.sizeMax() - fish.sizeMin()) * x;
        return new Rolled(Math.round(size * 10) / 10.0, Quality.of(stars), capped);
    }

    static int starsFor(double x, double[] bounds) {
        int stars = 1;
        for (double bound : bounds) {
            if (x >= bound) {
                stars++;
            }
        }
        return Math.min(stars, Quality.MAX);
    }
}
