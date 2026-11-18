package wiki.creeper.harbor.fishing.content;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Grade;

/** balance.yml의 fishing 절 (docs 20 3.3). 값이 없으면 '7. 밸런스 기획'의 값(또는 제안값)을 쓴다. */
public record FishingBalance(Balance balance) {

    public double gradeWeight(Grade grade) {
        double fallback =
                switch (grade) {
                    case COMMON -> 60;
                    case UNCOMMON -> 25;
                    case RARE -> 10;
                    case EPIC -> 4;
                    case LEGENDARY, MYTHIC -> 0;
                };
        return balance.number("fishing.grade_weight." + grade.key(), fallback);
    }

    /** 희귀 이상 보정 합계 상한 (+60%). */
    public double rareBonusCap() {
        return balance.number("fishing.rare_bonus_cap", 0.60);
    }

    public double legendBase() {
        return balance.number("fishing.legend.base_chance", 0.004);
    }

    public int legendPityStart() {
        return balance.integer("fishing.legend.pity_start", 150);
    }

    public double legendPityStep() {
        return balance.number("fishing.legend.pity_step", 0.0002);
    }

    public double legendMax() {
        return balance.number("fishing.legend.max_chance", 0.025);
    }

    public double mythicChance() {
        return balance.number("fishing.mythic.event_chance", 0.01);
    }

    /** 품질 경계 (★2·★3·★4·★5가 시작하는 백분위). */
    public double[] qualityBounds() {
        return new double[] {
            balance.number("fishing.quality.star2", 0.40),
            balance.number("fishing.quality.star3", 0.70),
            balance.number("fishing.quality.star4", 0.90),
            balance.number("fishing.quality.star5", 0.98)
        };
    }

    /** 입질 대기 (초, 제안값 15~35). */
    public double biteWaitMin() {
        return balance.number("fishing.bite_wait_s.min", 15);
    }

    public double biteWaitMax() {
        return balance.number("fishing.bite_wait_s.max", 35);
    }

    public double noBaitWaitMult() {
        return balance.number("fishing.no_bait_wait", 1.2);
    }

    public int gradeExp(Grade grade) {
        int fallback =
                switch (grade) {
                    case COMMON -> 5;
                    case UNCOMMON -> 12;
                    case RARE -> 40;
                    case EPIC -> 150;
                    case LEGENDARY -> 800;
                    case MYTHIC -> 2000;
                };
        return balance.integer("fishing.grade_exp." + grade.key(), fallback);
    }

    /** 지역 배율 (항구 1.0 / 늪지 1.1 / …). 키는 지역 ID의 마지막 마디. */
    public double regionExpMult(String regionName) {
        return balance.number("fishing.region_exp_mult." + regionName, 1.0);
    }

    public double firstCatchMult(Grade grade) {
        return grade.isAtLeast(Grade.LEGENDARY)
                ? balance.number("fishing.first_catch_mult.legendary", 3)
                : balance.number("fishing.first_catch_mult.default", 5);
    }

    public double lineStrengthStep() {
        return balance.number("fishing.line_strength_step", 0.08);
    }

    public double pingCompensationSeconds() {
        return balance.number("fishing.ping_comp_s", 0.2);
    }

    /** 입질 창 (틱, 제안값 20). 핑 보정은 ping_comp_s를 더한다. */
    public int biteWindowTicks() {
        return balance.integer("fishing.bite_window_ticks", 20);
    }

    /** 텐션 경고(화면 가장자리 붉게) 시작 값. */
    public double tensionWarn() {
        return balance.number("fishing.minigame.warn_tension", 0.85);
    }

    /** 원투 기준 거리 (블록, 이상이면 tension_safe_zone에 long_cast_safe_zone을 더한다). */
    public double longCastDistance() {
        return balance.number("fishing.long_cast.distance", 20);
    }

    // ------------------------------------------------------------------ 통발 ('7. 밸런스 기획' 8장)

    /** 주기 길이 기본 (시간). */
    public double trapCycleHours() {
        return balance.number("fishing.trap.cycle_h", 8);
    }

    /** 통발 회전으로 줄어도 이보다 짧아지지 않는다 (제안값). */
    public double trapMinCycleHours() {
        return balance.number("fishing.trap.min_cycle_h", 2);
    }

    /** 접속하지 않아도 쌓이는 최대 주기 수. */
    public int trapMaxCycles() {
        return balance.integer("fishing.trap.max_cycles", 2);
    }

    /** 슬롯당 주기마다 쓰는 미끼 수. */
    public int trapBaitPerSlot() {
        return balance.integer("fishing.trap.bait_per_slot", 1);
    }

    /** 통발 하나에 넣어 둘 수 있는 미끼 수 (제안값). */
    public int trapBaitCapacity() {
        return balance.integer("fishing.trap.bait_capacity", 64);
    }

    /** 기본 설치 개수 (생산 2차·3차 trap_count 각 +1). */
    public int trapBaseCount() {
        return balance.integer("fishing.trap.base_count", 2);
    }

    public int trapMaxCount() {
        return balance.integer("fishing.trap.max_count", 4);
    }

    /** 수익 상한 비율 (D1): 기본 0.4, 생산 계열 2차 0.5, 3차 0.6. */
    public double trapRevenueRatio(boolean produceLine, int tier) {
        if (produceLine && tier >= 3) {
            return balance.number("fishing.trap.revenue_cap.produce_3", 0.6);
        }
        if (produceLine && tier >= 2) {
            return balance.number("fishing.trap.revenue_cap.produce_2", 0.5);
        }
        return balance.number("fishing.trap.revenue_cap.base", 0.4);
    }

    /** 수동 낚시 시간당 골드 ('7. 밸런스 기획' 3.2). 키는 지역 ID의 마지막 마디. */
    public double hourlyGold(String regionName) {
        return balance.number("fishing.trap.hourly_gold." + regionName, 0);
    }

    /** 수익 창 길이 (시간). */
    public double trapRevenueWindowHours() {
        return balance.number("fishing.trap.revenue_window_h", 24);
    }

    /** 슬롯 확장 가격 = 이 값 × 통발 단계 ('7. 밸런스 기획' 6.3). */
    public long trapSlotPrice() {
        return Math.round(balance.number("fishing.trap.slot_price", 2000));
    }

    /** 통발 하나에 살 수 있는 확장 슬롯 수 (제안값). */
    public int trapMaxExtraSlots() {
        return balance.integer("fishing.trap.max_extra_slots", 3);
    }

    /** 수확하지 않으면 자동 회수까지 (일). */
    public int trapRetrieveDays() {
        return balance.integer("fishing.trap.retrieve_days", 7);
    }

    /** 부표 자리 인정 거리 (블록, 제안값). */
    public double trapBuoyRadius() {
        return balance.number("fishing.trap.buoy_radius", 2.5);
    }

    /** 품질 판매가 배율 (D2, economy.quality_mult를 같이 읽는다). */
    public double qualityMult(int stars) {
        double fallback =
                switch (stars) {
                    case 2 -> 1.2;
                    case 3 -> 1.5;
                    case 4 -> 2.5;
                    case 5 -> 5.0;
                    default -> 1.0;
                };
        return balance.number("economy.quality_mult." + stars, fallback);
    }

    // ------------------------------------------------------------------ 요리 · 신화

    /** 연회 요리로 버프를 나누는 거리 (블록, 제안값). */
    public double feastRadius() {
        return balance.number("fishing.food.feast_radius", 16);
    }

    /** 미니게임 등급 수치. */
    public MinigameSpec minigame(Grade grade) {
        String base = "fishing.minigame." + grade.key() + ".";
        // 기본값: '7. 밸런스 기획' 5장 (텐션 상승은 제안값)
        double[] defaults =
                switch (grade) {
                    case COMMON -> new double[] {100, 0.012, 0, 0};
                    case UNCOMMON -> new double[] {180, 0.018, 1, 4};
                    case RARE -> new double[] {350, 0.022, 2, 3};
                    case EPIC -> new double[] {600, 0.030, 3, 2.5};
                    case LEGENDARY -> new double[] {1200, 0.035, 4, 2};
                    case MYTHIC -> new double[] {1500, 0.038, 4, 2};
                };
        return new MinigameSpec(
                balance.number(base + "hp", defaults[0]),
                balance.number(base + "tension_rise", defaults[1]),
                balance.number("fishing.minigame.release_rate", 0.03),
                balance.number("fishing.minigame.reel_per_tick", 1.6),
                balance.integer(base + "patterns", (int) defaults[2]),
                balance.number(base + "pattern_interval_s", defaults[3]),
                balance.number("fishing.minigame.safe_low", 0.25),
                balance.number("fishing.minigame.safe_width", 0.45),
                balance.integer("fishing.minigame.escape_ticks", 40),
                grade.isAtLeast(Grade.LEGENDARY));
    }

    /**
     * 미니게임 수치 (제안값은 balance.yml에서 덮어쓴다).
     *
     * @param tensionRise 감는 중 틱당 텐션 상승
     * @param reelPerTick reel_speed 1.0일 때 틱당 체력 감소
     * @param safeLow 안전 구간 아래 끝
     * @param safeWidth 안전 구간 기본 폭 (tension_safe_zone 스탯을 곱함)
     * @param phased 전설 3페이즈
     */
    public record MinigameSpec(
            double hp,
            double tensionRise,
            double releaseRate,
            double reelPerTick,
            int patterns,
            double patternIntervalSeconds,
            double safeLow,
            double safeWidth,
            int escapeTicks,
            boolean phased) {}
}
