package wiki.creeper.harbor.fishing.content;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Grade;

/**
 * The fishing section of balance.yml (docs 20 3.3). Missing values fall back to "7. Balance design"
 * values (or proposed values).
 */
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

    /** Cap on the total rare-or-better modifier (+60%). */
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

    /** Quality thresholds (percentiles where ★2, ★3, ★4, ★5 start). */
    public double[] qualityBounds() {
        return new double[] {
            balance.number("fishing.quality.star2", 0.40),
            balance.number("fishing.quality.star3", 0.70),
            balance.number("fishing.quality.star4", 0.90),
            balance.number("fishing.quality.star5", 0.98)
        };
    }

    /** Bite wait (seconds, proposed value 15~35). */
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

    /**
     * Region multipliers (harbor 1.0 / swamp 1.1 / …). Keys are the last segment of the region ID.
     */
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

    /** Today's date for the daily catch summary (fishing.daily_zone, proposed Asia/Seoul). */
    public java.time.LocalDate today() {
        return java.time.LocalDate.now(dailyZone());
    }

    /** Time zone of the daily catch summary day. */
    public java.time.ZoneId dailyZone() {
        String zone = balance.string("fishing.daily_zone").orElse("Asia/Seoul");
        try {
            return java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            return java.time.ZoneId.of("Asia/Seoul");
        }
    }

    /** Feast rank 2 shared buff duration bonus ("10. Skill tree" produce 3: +50%). */
    public double feastShareBonus() {
        return balance.number("fishing.food.feast_share_bonus", 0.5);
    }

    /** Bite window (ticks, proposed value 20). Ping compensation adds ping_comp_s. */
    public int biteWindowTicks() {
        return balance.integer("fishing.bite_window_ticks", 20);
    }

    /** Value at which the tension warning (red screen edges) starts. */
    public double tensionWarn() {
        return balance.number("fishing.minigame.warn_tension", 0.85);
    }

    /**
     * Long cast reference distance (blocks; at or beyond it, long_cast_safe_zone is added to
     * tension_safe_zone).
     */
    public double longCastDistance() {
        return balance.number("fishing.long_cast.distance", 20);
    }

    // ------------------------------------------------------------------ Traps ("7. Balance design"
    // ch. 8)

    /** Base cycle length (hours). */
    public double trapCycleHours() {
        return balance.number("fishing.trap.cycle_h", 8);
    }

    /** Trap turnover never shortens it below this (proposed value). */
    public double trapMinCycleHours() {
        return balance.number("fishing.trap.min_cycle_h", 2);
    }

    /** Maximum cycles that accumulate while offline. */
    public int trapMaxCycles() {
        return balance.integer("fishing.trap.max_cycles", 2);
    }

    /** Bait used per slot per cycle. */
    public int trapBaitPerSlot() {
        return balance.integer("fishing.trap.bait_per_slot", 1);
    }

    /** Bait that one trap can hold (proposed value). */
    public int trapBaitCapacity() {
        return balance.integer("fishing.trap.bait_capacity", 64);
    }

    /** Base placement count (production 2nd/3rd tier trap_count +1 each). */
    public int trapBaseCount() {
        return balance.integer("fishing.trap.base_count", 2);
    }

    public int trapMaxCount() {
        return balance.integer("fishing.trap.max_count", 4);
    }

    /** Income cap ratio (D1): base 0.4, production line 2nd tier 0.5, 3rd tier 0.6. */
    public double trapRevenueRatio(boolean produceLine, int tier) {
        if (produceLine && tier >= 3) {
            return balance.number("fishing.trap.revenue_cap.produce_3", 0.6);
        }
        if (produceLine && tier >= 2) {
            return balance.number("fishing.trap.revenue_cap.produce_2", 0.5);
        }
        return balance.number("fishing.trap.revenue_cap.base", 0.4);
    }

    /**
     * Manual fishing gold per hour ("7. Balance design" 3.2). Keys are the last segment of the
     * region ID.
     */
    public double hourlyGold(String regionName) {
        return balance.number("fishing.trap.hourly_gold." + regionName, 0);
    }

    /** Income window length (hours). */
    public double trapRevenueWindowHours() {
        return balance.number("fishing.trap.revenue_window_h", 24);
    }

    /** Slot expansion price = this value × trap tier ("7. Balance design" 6.3). */
    public long trapSlotPrice() {
        return Math.round(balance.number("fishing.trap.slot_price", 2000));
    }

    /** Extra slots purchasable per trap (proposed value). */
    public int trapMaxExtraSlots() {
        return balance.integer("fishing.trap.max_extra_slots", 3);
    }

    /** Time until auto-retrieval if not harvested (days). */
    public int trapRetrieveDays() {
        return balance.integer("fishing.trap.retrieve_days", 7);
    }

    /** Distance at which a buoy spot counts (blocks, proposed value). */
    public double trapBuoyRadius() {
        return balance.number("fishing.trap.buoy_radius", 2.5);
    }

    /** Quality sell price multipliers (D2, also reads economy.quality_mult). */
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

    // ------------------------------------------------------------------ Food · mythic

    /** Distance within which a feast dish shares its buff (blocks, proposed value). */
    public double feastRadius() {
        return balance.number("fishing.food.feast_radius", 16);
    }

    /** Minigame grade values. */
    public MinigameSpec minigame(Grade grade) {
        String base = "fishing.minigame." + grade.key() + ".";
        // Defaults: "7. Balance design" chapter 5 (tension rise is a proposed value)
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
     * Minigame values (proposed values are overridden in balance.yml).
     *
     * @param tensionRise tension rise per tick while reeling
     * @param reelPerTick health loss per tick at reel_speed 1.0
     * @param safeLow lower end of the safe zone
     * @param safeWidth base width of the safe zone (multiplied by the tension_safe_zone stat)
     * @param phased legendary 3 phases
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
