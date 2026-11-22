package wiki.creeper.harbor.boss.content;

import wiki.creeper.harbor.boss.logic.ContributionWeights;
import wiki.creeper.harbor.common.content.type.Balance;

/**
 * The {@code boss} section of balance.yml (docs 20 10.4, '7. Balance design' chapter 9). Missing
 * values fall back to the documented values (type table) or proposed values. Has no server API, so
 * unit tests use it too.
 */
public record BossBalance(Balance balance) {

    private static final String T = "boss.types.";

    /** Empty balance (for tests, all defaults). */
    public static BossBalance defaults() {
        return new BossBalance(Balance.of(java.util.Map.of()));
    }

    // ------------------------------------------------------------------ Type defaults ('7. Balance
    // design' chapter 9)

    /** HP increase per player: intro 0.60 · sea 0.50 · dimension 0.45 · raid 0.40. */
    public double perPlayerRate(BossType type) {
        double fallback =
                switch (type) {
                    case INTRO -> 0.60;
                    case SEA -> 0.50;
                    case DIMENSION -> 0.45;
                    case RAID -> 0.40;
                    case WORLD -> 0.0;
                };
        return balance.number(T + type.key() + ".per_player_rate", fallback);
    }

    /** Time limit (seconds): intro 360 · sea 480 · dimension 720 · raid 1,200 · world 1,800. */
    public int timeLimitSeconds(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 360;
                    case SEA -> 480;
                    case DIMENSION -> 720;
                    case RAID -> 1200;
                    case WORLD -> 1800;
                };
        return balance.integer(T + type.key() + ".time_limit", fallback);
    }

    /** Weekly reward count: intro 5 · sea 3 · dimension 2 · raid 1 · world 1. */
    public int weeklyLimit(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 5;
                    case SEA -> 3;
                    case DIMENSION -> 2;
                    case RAID, WORLD -> 1;
                };
        return Math.max(1, balance.integer(T + type.key() + ".weekly", fallback));
    }

    /**
     * Clear gold (proposed value): intro 300 · sea 800 · dimension 1,500 · raid 3,000 · world
     * 2,000.
     */
    public long clearGold(BossType type) {
        long fallback =
                switch (type) {
                    case INTRO -> 300;
                    case SEA -> 800;
                    case DIMENSION -> 1500;
                    case RAID -> 3000;
                    case WORLD -> 2000;
                };
        return (long) balance.number(T + type.key() + ".gold", (double) fallback);
    }

    /**
     * Telegraph time (ticks, proposed value): intro 1.5s · sea 1.2s · dimension 1.0s · raid 0.9s ·
     * world 1.5s.
     */
    public int telegraphTicks(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO, WORLD -> 30;
                    case SEA -> 24;
                    case DIMENSION -> 20;
                    case RAID -> 18;
                };
        return balance.integer(T + type.key() + ".telegraph_ticks", fallback);
    }

    /** Base gap between patterns (ticks, proposed value). */
    public int patternGapTicks(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 100;
                    case SEA -> 80;
                    case DIMENSION -> 70;
                    case RAID -> 60;
                    case WORLD -> 60;
                };
        return balance.integer(T + type.key() + ".pattern_gap_ticks", fallback);
    }

    /** Anchors required to start: 1 by default, 2 for raids (docs 20 10.4 stage 2). */
    public int anchorsMin(BossType type) {
        return balance.integer(T + type.key() + ".anchors_min", type == BossType.RAID ? 2 : 1);
    }

    // ------------------------------------------------------------------ Latency compensation ('7.
    // Balance design' chapter 5)

    public int latencyPingMs() {
        return balance.integer("boss.latency.ping_ms", 150);
    }

    /** Telegraph +0.2s if any party member has a ping of 150ms or more. */
    public int latencyExtraTicks() {
        return balance.integer("boss.latency.extra_ticks", 4);
    }

    // ------------------------------------------------------------------ Progress

    public int prepareTicks() {
        return balance.integer("boss.prepare_seconds", 30) * 20;
    }

    public int introTicks() {
        return balance.integer("boss.intro_seconds", 5) * 20;
    }

    /** Safe-zone hold time needed to fill the hook gauge (proposed value 20s). */
    public int hookTicks() {
        return (int) Math.round(balance.number("boss.hook.seconds", 20) * 20);
    }

    /** When an anchor line snaps, the hook gauge is reset to at most this value (50%). */
    public double hookLineBreakCap() {
        return balance.number("boss.hook.line_break_cap", 0.5);
    }

    /** Hook gauge decay while there is no anchor (5% per second). */
    public double hookDecayPerSecond() {
        return balance.number("boss.hook.decay_per_second", 0.05);
    }

    /** Time after a line snaps before it can be hooked again (ticks, proposed value). */
    public int hookReopenTicks() {
        return balance.integer("boss.hook.reopen_ticks", 40);
    }

    /** Anchor swap grace period (tension frozen, 5s). */
    public int anchorGraceTicks() {
        return balance.integer("boss.hook.anchor_grace_seconds", 5) * 20;
    }

    public double anchorSafeMin() {
        return balance.number("boss.anchor.safe_min", 0.2);
    }

    public double anchorSafeMax() {
        return balance.number("boss.anchor.safe_max", 0.85);
    }

    /**
     * Reeling damage per second while holding the safe zone, relative to max HP (0.3%, proposed
     * value).
     */
    public double anchorReelDamagePerSecond() {
        return balance.number("boss.anchor.reel_damage_per_second", 0.003);
    }

    /** Anchor tension tier multiplier (tension rise per boss type, proposed value). */
    public double anchorTensionMult(BossType type) {
        return balance.number(T + type.key() + ".tension_mult", 1.0);
    }

    public int weakIntervalTicks() {
        return (int) Math.round(balance.number("boss.counter.weak_interval_seconds", 12) * 20);
    }

    public int weakTicks() {
        return (int) Math.round(balance.number("boss.counter.weak_seconds", 4) * 20);
    }

    /**
     * While an anchor holds the safe zone, the weak point exposure timer fills faster by this
     * multiplier (proposed value).
     */
    public double weakSafeSpeedup() {
        return balance.number("boss.counter.safe_speedup", 1.5);
    }

    // ------------------------------------------------------------------ Damage

    public double weakMult() {
        return balance.number("boss.damage.weak", 3.0);
    }

    public double normalMult() {
        return balance.number("boss.damage.normal", 1.0);
    }

    public double armorMult() {
        return balance.number("boss.damage.armor", 0.2);
    }

    /** Harpoon melee stab multiplier (proposed). */
    public double stabMult() {
        return balance.number("boss.damage.stab", 0.5);
    }

    public int stabCooldownTicks() {
        return balance.integer("boss.damage.stab_cooldown_ticks", 10);
    }

    /** Base harpoon damage when the item stats do not define it (proposed value). */
    public double harpoonBaseDamage() {
        return balance.number("boss.damage.harpoon_base", 20);
    }

    /** Maximum harpoon tracking ticks (proposed value). */
    public int harpoonTrackTicks() {
        return balance.integer("boss.damage.harpoon_track_ticks", 60);
    }

    // ------------------------------------------------------------------ QTE

    public int qteRounds() {
        return balance.integer("boss.qte.rounds", 3);
    }

    public int qteInputs() {
        return balance.integer("boss.qte.inputs", 4);
    }

    public int qteWindowTicks() {
        return (int) Math.round(balance.number("boss.qte.window_seconds", 1.0) * 20);
    }

    public double qtePass() {
        return balance.number("boss.qte.pass", 0.6);
    }

    public int qteWin() {
        return balance.integer("boss.qte.win", 2);
    }

    public int qteLose() {
        return balance.integer("boss.qte.lose", 2);
    }

    /** HP ratio restored when the tug-of-war fails (20%, at most once). */
    public double qteRecover() {
        return balance.number("boss.qte.recover", 0.2);
    }

    /** Rest ticks between rounds (proposed value). */
    public int qteGapTicks() {
        return balance.integer("boss.qte.gap_ticks", 30);
    }

    // ------------------------------------------------------------------ Contribution

    public ContributionWeights weights() {
        return new ContributionWeights(
                balance.number("boss.contribution.damage", 0.3),
                balance.number("boss.contribution.tension", 0.3),
                balance.number("boss.contribution.repair", 0.2),
                balance.number("boss.contribution.evasion", 0.2));
    }

    /** Top reward ratio (20%, rounded up). */
    public double topRatio() {
        return balance.number("boss.contribution.top_ratio", 0.2);
    }

    /** Minimum participation: if the score is below 5% of the top score and */
    public double minScoreRatio() {
        return balance.number("boss.contribution.min_score_ratio", 0.05);
    }

    /** active time is below 30% of the fight duration, no reward. */
    public double minActiveRatio() {
        return balance.number("boss.contribution.min_active_ratio", 0.3);
    }

    /** A player counts as active for this long after their last activity (proposed value). */
    public int activeWindowTicks() {
        return balance.integer("boss.contribution.active_window_seconds", 5) * 20;
    }

    /**
     * Quartermaster support per buff food use = nearby party members × this many seconds (proposed
     * value).
     */
    public int supplySeconds() {
        return balance.integer("boss.contribution.supply_seconds", 60);
    }

    // ------------------------------------------------------------------ Rewards

    /** Gold ratio for clears beyond the weekly count (20%). */
    public double overLimitGoldRatio() {
        return balance.number("boss.loot.over_limit_gold_ratio", 0.2);
    }

    /** Added to drop chance when there is a quartermaster (proposed). */
    public double quartermasterBonus() {
        return balance.number("boss.loot.quartermaster_bonus", 0.1);
    }

    /** Boss fish quality bonus: luck +0.3 (proposed value). */
    public double captureLuck() {
        return balance.number("boss.loot.capture_luck", 0.3);
    }

    public int captureQualityStars() {
        return balance.integer("boss.loot.capture_quality_stars", 1);
    }

    // ------------------------------------------------------------------ Leaving and limits

    /** Slot held after leaving (60s). */
    public int slotHoldTicks() {
        return balance.integer("boss.leave.slot_hold_seconds", 60) * 20;
    }

    /** Fails if all party members stay away for this long (60s). */
    public int allAbsentFailTicks() {
        return balance.integer("boss.leave.all_absent_fail_seconds", 60) * 20;
    }

    /** Return to deck after falling into the water (5s). */
    public int fallTicks() {
        return balance.integer("boss.leave.fall_seconds", 5) * 20;
    }

    /** Concurrent field encounter cap on the main server (6, proposed value). */
    public int maxFieldEncounters() {
        return balance.integer("boss.limits.field", 6);
    }

    /** Concurrent encounter cap on instance servers (4, proposed value). */
    public int maxInstanceEncounters() {
        return balance.integer("boss.limits.instance", 4);
    }

    public int inviteSeconds() {
        return balance.integer("boss.party.invite_seconds", 60);
    }

    // ------------------------------------------------------------------ World boss

    /** Minimum number of valid participants (10, confirmed). */
    public int worldMinParticipants() {
        return balance.integer("boss.world.min_participants", 10);
    }

    /** Window for valid participation (last 60 seconds). */
    public int worldActiveTicks() {
        return balance.integer("boss.world.active_seconds", 60) * 20;
    }

    public int worldRescaleTicks() {
        return balance.integer("boss.world.rescale_seconds", 10) * 20;
    }

    public int worldBroadcastTicks() {
        return balance.integer("boss.world.broadcast_seconds", 2) * 20;
    }

    public int worldSaveTicks() {
        return balance.integer("boss.world.save_seconds", 10) * 20;
    }

    /** How long the fight can be resumed after the main server dies (10 minutes). */
    public int worldResumeMinutes() {
        return balance.integer("boss.world.resume_minutes", 10);
    }

    /** World boss display and participation radius (64 blocks). */
    public double worldRadius() {
        return balance.number("boss.world.radius", 64);
    }

    /** Number of players who can hook a line at the same time (proposed value). */
    public int worldMaxAnchors() {
        return balance.integer("boss.world.max_anchors", 20);
    }

    // ------------------------------------------------------------------ Instances

    public int instanceSlots() {
        return balance.integer("boss.instance.slots", 4);
    }

    /** Spacing between cells of the same arena (1,000 blocks, X axis). */
    public double instanceSpacing() {
        return balance.number("boss.instance.spacing", 1000);
    }

    /** Wait for an assignment response (10s). */
    public int instanceReplyTicks() {
        return balance.integer("boss.instance.reply_seconds", 10) * 20;
    }

    /** Wait for a reserved cell to be confirmed (proposed value). */
    public int instanceConfirmTicks() {
        return balance.integer("boss.instance.confirm_seconds", 15) * 20;
    }

    /** Wait for party members to arrive (60s). */
    public int instanceArriveTicks() {
        return balance.integer("boss.instance.arrive_seconds", 60) * 20;
    }

    /** harbor:boss:rejoin expiry (120s). */
    public int rejoinTtlSeconds() {
        return balance.integer("boss.instance.rejoin_ttl_seconds", 120);
    }
}
