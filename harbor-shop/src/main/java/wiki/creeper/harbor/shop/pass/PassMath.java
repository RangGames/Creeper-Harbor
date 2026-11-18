package wiki.creeper.harbor.shop.pass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.shop.content.PassDef;

/**
 * 시즌 패스 계산 (서버 없이 테스트한다). 단계 = min(최대 단계, 누적 경험치 ÷ 단계당 경험치 + 구매 단계). 경험치는 누적값만 저장하고 줄지 않으므로 지연 쓰기
 * 저장이 늦게 도착해도 {@code GREATEST}로 합치면 된다. 구매 단계(bought)는 즉시 트랜잭션에서만 바꾼다.
 */
public final class PassMath {

    /** 프리미엄 단계 (season_pass.premium). */
    public static final int FREE = 0;

    public static final int PREMIUM = 1;
    public static final int PREMIUM_PLUS = 2;

    private PassMath() {}

    /** 누적 경험치와 구매 단계로 현재 단계 (0 = 아직 1단계 전). */
    public static int level(PassDef pass, long exp, int bought) {
        long byExp = Math.max(0, exp) / pass.expPerLevel();
        return (int) Math.min(pass.levels(), byExp + Math.max(0, bought));
    }

    /** 다음 단계까지의 진행 (0 이상 expPerLevel 미만). 최대 단계면 expPerLevel. */
    public static long progress(PassDef pass, long exp, int bought) {
        if (level(pass, exp, bought) >= pass.levels()) {
            return pass.expPerLevel();
        }
        return Math.max(0, exp) % pass.expPerLevel();
    }

    /** 최대 단계까지 살 수 있는 단계 수. */
    public static int buyableLevels(PassDef pass, long exp, int bought) {
        return pass.levels() - level(pass, exp, bought);
    }

    /** 단계 n개 구매 가격. 최대 단계를 넘으면 -1. */
    public static long levelPrice(PassDef pass, long exp, int bought, int count) {
        if (count <= 0 || count > buyableLevels(pass, exp, bought)) {
            return -1;
        }
        return (long) count * pass.prices().level();
    }

    /** 프리미엄 단계를 올리는 가격. 0→1 premium, 0→2 premium_plus, 1→2 upgrade. 내리거나 같으면 -1. */
    public static long premiumPrice(PassDef pass, int from, int to) {
        if (to <= from || from < FREE || to > PREMIUM_PLUS) {
            return -1;
        }
        if (from == FREE) {
            return to == PREMIUM ? pass.prices().premium() : pass.prices().premiumPlus();
        }
        return pass.prices().upgrade();
    }

    /** 프리미엄+로 올릴 때 즉시 주는 단계 (이미 프리미엄+면 0). */
    public static int premiumPlusBonus(PassDef pass, int from, int to) {
        return from < PREMIUM_PLUS && to == PREMIUM_PLUS ? pass.premiumPlusLevels() : 0;
    }

    /** 포획 경험치 출처 키 (catch.&lt;등급&gt;). */
    public static String catchSource(Grade grade) {
        return "catch." + grade.key();
    }

    /** 경험치를 더한 결과. 최대 단계 뒤에도 경험치는 쌓인다 (표시용 누적). */
    public static long addExp(long exp, long amount) {
        return amount <= 0 ? exp : Math.addExact(exp, amount);
    }

    /**
     * 받을 수 있지만 아직 받지 않은 보상. 무료 트랙은 단계 도달 즉시, 프리미엄은 프리미엄을 늦게 사면 이미 도달한 단계를 한꺼번에.
     *
     * @param claimed 이미 받은 보상 키 (단계 + ":" + claimKey)
     */
    public static List<PassDef.Reward> claimable(
            PassDef pass, int level, int premium, Set<String> claimed) {
        List<PassDef.Reward> result = new ArrayList<>();
        for (PassDef.Reward reward : pass.rewards()) {
            if (reward.level() <= level
                    && reward.track().openAt(premium)
                    && !claimed.contains(claimKey(reward))) {
                result.add(reward);
            }
        }
        return result;
    }

    /** 받은 보상을 구분하는 키 (season_pass_claim의 level + track 열). */
    public static String claimKey(PassDef.Reward reward) {
        return reward.level() + ":" + reward.claimKey();
    }

    /** 트랙별 진주 보상 합계 (무료 150 · 프리미엄 1,000 확인용). */
    public static int pearlTotal(PassDef pass, PassDef.Track track) {
        return pass.rewards().stream()
                .filter(r -> r.track() == track)
                .mapToInt(PassDef.Reward::pearls)
                .sum();
    }
}
