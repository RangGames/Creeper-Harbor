package wiki.creeper.harbor.fishing.roll;

import java.util.EnumMap;
import java.util.Map;

/**
 * 희귀 이상 출현 보정 합성 (docs 20 3.4 5단계, '7. 밸런스 기획' 4.2). 최종 배율 = (1 + 미끼)(1 + 직업)(1 + 요리)(1 + 이벤트)이고,
 * 합계 배율은 1 + 상한(0.6)을 넘지 않는다. 범주별 값은 보정자 출처 접두사로 묶은 ADD 합이고, 요리는 가장 큰 1종만 쓴다. 서버 API 없음 (단위 테스트).
 */
public final class GradeBonus {

    /** 보정 범주. */
    public enum Category {
        /** 미끼·장비 (bait:, 낚싯대·미끼 아이템 stats). */
        BAIT,
        /** 직업 (job:, skill:, 그 밖의 출처). */
        JOB,
        /** 요리·도감·수족관 (food:, codex_season, aquarium_stage). */
        FOOD,
        /** 이벤트·낚시터 (event:, spot:). */
        EVENT
    }

    /** 범주별 누적기. */
    public static final class Sum {
        private final Map<Category, Double> sums = new EnumMap<>(Category.class);
        private double bestFood;

        /** 보정자 하나를 출처로 분류해 더한다. */
        public Sum add(String source, double value) {
            Category category = categoryOf(source);
            if (source.startsWith("food:")) {
                // 요리는 가장 큰 1종만
                bestFood = Math.max(bestFood, value);
            } else {
                sums.merge(category, value, Double::sum);
            }
            return this;
        }

        public Sum add(Category category, double value) {
            sums.merge(category, value, Double::sum);
            return this;
        }

        public double of(Category category) {
            double v = sums.getOrDefault(category, 0.0);
            return category == Category.FOOD ? v + bestFood : v;
        }

        /** 범주 곱 − 1 (상한 적용 전). */
        public double combined() {
            double mult = 1;
            for (Category c : Category.values()) {
                mult *= 1 + of(c);
            }
            return mult - 1;
        }

        /** 상한 적용. */
        public double capped(double cap) {
            return Math.clamp(combined(), -1, cap);
        }
    }

    private GradeBonus() {}

    public static Category categoryOf(String source) {
        if (source.startsWith("bait:") || source.startsWith("gear:")) {
            return Category.BAIT;
        }
        if (source.startsWith("food:")
                || source.startsWith("codex_season")
                || source.startsWith("aquarium_stage")) {
            return Category.FOOD;
        }
        if (source.startsWith("event:") || source.startsWith("spot:")) {
            return Category.EVENT;
        }
        return Category.JOB;
    }
}
