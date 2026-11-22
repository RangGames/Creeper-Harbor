package wiki.creeper.harbor.wildlife.content;

import java.util.Map;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * balance.yml wildlife section ('17-3' 9.3, 9.4). Missing values fall back to documented or
 * proposed values. The 9.4 budget table, LOD table and threat detection values are all proposed
 * values.
 */
public record WildlifeBalance(Balance b) {

    // ---------------------------------------------------------------- budget (9.4 table)
    /** Target fish count per player. */
    public int targetPerPlayer() {
        return b.integer("wildlife.budget.target_per_player", 12);
    }

    /** Per-player display cap (fish count). */
    public int maxShownPerPlayer() {
        return b.integer("wildlife.budget.max_shown", 24);
    }

    /** Per-player display cap (parts). */
    public int maxPartsPerPlayer() {
        return b.integer("wildlife.budget.max_parts", 180);
    }

    /** Server-wide cap. */
    public int globalMax() {
        return b.integer("wildlife.budget.global_max", 2000);
    }

    public int schoolsPerPlayerPerSecond() {
        return b.integer("wildlife.budget.schools_per_player_s", 2);
    }

    public int spawnPerTick() {
        return b.integer("wildlife.budget.spawn_per_tick", 20);
    }

    /** Core's part cap around a player (same value as balance.yml rig.part_budget). */
    public int corePartBudget() {
        return b.integer("rig.part_budget", 300);
    }

    /**
     * Part budget multiplier per effects intensity (0 minimum, 1 normal, 2 maximum; Fishing
     * setting) (50, 75, 100%).
     */
    public double intensityScale(int level) {
        double fallback =
                switch (level) {
                    case 0 -> 0.5;
                    case 1 -> 0.75;
                    default -> 1.0;
                };
        return b.number("wildlife.budget.intensity." + level, fallback);
    }

    // ---------------------------------------------------------------- spawning and cleanup
    public double spawnRingMin() {
        return b.number("wildlife.spawn.ring_min", 16);
    }

    public double spawnRingMax() {
        return b.number("wildlife.spawn.ring_max", 40);
    }

    /** Minimum distance from other school centers. */
    public double schoolGap() {
        return b.number("wildlife.spawn.school_gap", 8);
    }

    /** Medium depth required for water/lava spawns (cells). */
    public int minLiquidDepth() {
        return b.integer("wildlife.spawn.min_depth", 2);
    }

    /** Free cells required for air spawns. */
    public int airClearance() {
        return b.integer("wildlife.spawn.air_clearance", 3);
    }

    /** Removed once this far from every player. */
    public double despawnDistance() {
        return b.number("wildlife.spawn.despawn", 56);
    }

    /** Body length below this is a small school (3~6), otherwise a large solitary fish (1~2). */
    public double smallLength() {
        return b.number("wildlife.spawn.small_length", 1.0);
    }

    public int smallSchoolMin() {
        return b.integer("wildlife.spawn.small_school.min", 3);
    }

    public int smallSchoolMax() {
        return b.integer("wildlife.spawn.small_school.max", 6);
    }

    public int largeSchoolMin() {
        return b.integer("wildlife.spawn.large_school.min", 1);
    }

    public int largeSchoolMax() {
        return b.integer("wildlife.spawn.large_school.max", 2);
    }

    /** Floor fish (shellfish) cap per chunk. */
    public int floorPerChunk() {
        return b.integer("wildlife.spawn.floor_per_chunk", 2);
    }

    /** Blocks floor fish can sit on (vanilla block keys). */
    public String floorBlocks() {
        return b.string("wildlife.spawn.floor_blocks")
                .orElse("sand,gravel,suspicious_sand,suspicious_gravel,mud,clay");
    }

    /**
     * Natural spawn grade weights ('7. Balance design' 4.1, same values as fishing.grade_weight).
     * Legendary and mythic are 0 (no natural spawns).
     */
    public double gradeWeight(Grade grade) {
        return switch (grade) {
            case LEGENDARY, MYTHIC -> 0;
            default -> {
                double fishing =
                        b.number("fishing.grade_weight." + grade.key(), defaultGrade(grade));
                yield b.number("wildlife.spawn.grade_weight." + grade.key(), fishing);
            }
        };
    }

    private static double defaultGrade(Grade grade) {
        return switch (grade) {
            case COMMON -> 60;
            case UNCOMMON -> 25;
            case RARE -> 10;
            case EPIC -> 4;
            default -> 0;
        };
    }

    /** Default boost multiplier for great migration etc. (when World gives no wildlifeBoost). */
    public double defaultBoost() {
        return b.number("wildlife.spawn.boost", 2.0);
    }

    // ---------------------------------------------------------------- LOD (9.4 table)
    public double nearDistance() {
        return b.number("wildlife.lod.near", 16);
    }

    /** Fish farther than this are not newly shown (prototype LOD 40). */
    public double showDistance() {
        return b.number("wildlife.lod.show", 40);
    }

    /** Already visible fish stay visible up to here (prevents flicker at the edge). */
    public double keepDistance() {
        return b.number("wildlife.lod.keep", 44);
    }

    public int nearMoveTicks() {
        return b.integer("wildlife.lod.near_ticks", 2);
    }

    public int midMoveTicks() {
        return b.integer("wildlife.lod.mid_ticks", 4);
    }

    /** Send interval for 16~40 blocks at auto-throttle level 1. */
    public int midMoveTicksSlow() {
        return b.integer("wildlife.lod.mid_ticks_slow", 6);
    }

    /** Steering update interval beyond 40 blocks (no viewers). */
    public double farHz() {
        return b.number("wildlife.lod.far_hz", 2);
    }

    /**
     * Time after the last viewer leaves before the rig entities are removed (proposed value). If it
     * becomes visible again in the meantime, the rig is reused.
     */
    public int hiddenGraceSeconds() {
        return b.integer("wildlife.lod.hidden_grace_s", 5);
    }

    /**
     * Interval for recounting other modules' rig parts (to compute the remaining share of Core's
     * 300).
     */
    public int otherPartsRefreshSeconds() {
        return b.integer("wildlife.lod.other_parts_refresh_s", 5);
    }

    // ---------------------------------------------------------------- simulation and apply
    public int simPeriodMillis() {
        return b.integer("wildlife.sim.period_ms", 100);
    }

    /** Main-thread apply budget (per tick). */
    public double applyBudgetMillis() {
        return b.number("wildlife.sim.apply_budget_ms", 1.0);
    }

    /** Auto-throttle when applying falls more than this many steps behind. */
    public int lagCycles() {
        return b.integer("wildlife.sim.lag_cycles", 2);
    }

    /** Display cap multiplier at auto-throttle level 2. */
    public double lagShownScale() {
        return b.number("wildlife.sim.lag_shown_scale", 0.75);
    }

    // ---------------------------------------------------------------- behaviour (9.4)
    public double homeRadius() {
        return b.number("wildlife.move.home_radius", 24);
    }

    public double retargetMin() {
        return b.number("wildlife.move.retarget_s.min", 3);
    }

    public double retargetMax() {
        return b.number("wildlife.move.retarget_s.max", 6);
    }

    /** Dice interval 1 second ± 0.3 seconds. */
    public double diceInterval() {
        return b.number("wildlife.move.dice_s", 1.0);
    }

    public double diceJitter() {
        return b.number("wildlife.move.dice_jitter_s", 0.3);
    }

    /** Rest speed multiplier (1 − 0.85). */
    public double idleSpeed() {
        return b.number("wildlife.move.idle_speed", 0.15);
    }

    /** Distance kept below the surface. */
    public double surfaceGap() {
        return b.number("wildlife.move.surface_gap", 0.5);
    }

    /** Forward probe distance = speed × this time. */
    public double probeAheadSeconds() {
        return b.number("wildlife.move.probe_ahead_s", 1.5);
    }

    /**
     * Body length → cruise speed (blocks/second) = body length × this value, clamped to the range
     * below (proposed value).
     */
    public double cruisePerLength() {
        return b.number("wildlife.move.cruise_per_length", 2.0);
    }

    public double cruiseMin() {
        return b.number("wildlife.move.cruise_min", 0.6);
    }

    public double cruiseMax() {
        return b.number("wildlife.move.cruise_max", 2.2);
    }

    /** Max turn rate per template (fish 180°/s, ray 90°/s, jelly 30°/s, proposed value). */
    public double turnRate(WildTemplate template) {
        double fallback =
                switch (template) {
                    case FISH -> 180;
                    case CEPH -> 120;
                    case RAY -> 90;
                    case SEAHORSE -> 60;
                    case JELLY -> 30;
                    case CLAM -> 0;
                };
        return b.number("wildlife.move.turn_rate." + template.key(), fallback);
    }

    /** Tilt limit (±45°, seahorse ±20°). */
    public double pitchLimit(WildTemplate template) {
        double fallback =
                template == WildTemplate.SEAHORSE ? 20 : template == WildTemplate.CLAM ? 0 : 45;
        return b.number("wildlife.move.pitch_limit." + template.key(), fallback);
    }

    /**
     * School steering weights (separation, alignment, cohesion, leader following; proposed value).
     */
    public Map<String, Double> flockWeights() {
        return Map.of(
                "separation", b.number("wildlife.school.separation", 1.6),
                "alignment", b.number("wildlife.school.alignment", 0.8),
                "cohesion", b.number("wildlife.school.cohesion", 0.6),
                "leader", b.number("wildlife.school.leader", 1.0));
    }

    /** School neighbour radius (proposed value). */
    public double flockRadius() {
        return b.number("wildlife.school.radius", 4);
    }

    /** Separation distance = body length × this value (proposed value). */
    public double separationLengths() {
        return b.number("wildlife.school.separation_lengths", 1.5);
    }

    // ---------------------------------------------------------------- threat (9.4)
    public double sneakFactor() {
        return b.number("wildlife.threat.sneak", 0.5);
    }

    public double boatFactor() {
        return b.number("wildlife.threat.boat", 1.3);
    }

    /** Triggers when approached at this speed or faster (blocks/second). */
    public double closingSpeed() {
        return b.number("wildlife.threat.closing_speed", 2.5);
    }

    /** Triggers when something comes within this fraction of the radius. */
    public double innerRatio() {
        return b.number("wildlife.threat.inner_ratio", 0.35);
    }

    public double fleeMinSeconds() {
        return b.number("wildlife.threat.flee_s.min", 3);
    }

    public double fleeMaxSeconds() {
        return b.number("wildlife.threat.flee_s.max", 5);
    }

    /** Flee speed ×(1 + 1.4). */
    public double fleeSpeed() {
        return b.number("wildlife.threat.flee_speed", 1.4);
    }

    /** Spread distance within a school. */
    public double spreadRadius() {
        return b.number("wildlife.threat.spread", 4);
    }

    public double habituateSeconds() {
        return b.number("wildlife.threat.habituate_s", 30);
    }

    public double habituateFactor() {
        return b.number("wildlife.threat.habituate", 0.7);
    }

    /** Radius of the threat reaction when a line snaps (proposed value). */
    public double lineBreakRadius() {
        return b.number("wildlife.threat.line_break_radius", 8);
    }

    // ---------------------------------------------------------------- feeding (9.4)
    public double baitRadius() {
        return b.number("wildlife.bait.radius", 10);
    }

    public int baitMax() {
        return b.integer("wildlife.bait.max", 2);
    }

    public double orbitRadius() {
        return b.number("wildlife.bait.orbit", 1.5);
    }

    /** Orbit angular speed (degrees/second, proposed value). */
    public double orbitSpeed() {
        return b.number("wildlife.bait.orbit_speed", 40);
    }

    // ---------------------------------------------------------------- medium (9.4)
    /** Air height band (island height −10 ~ +20). */
    public double airBandBelow() {
        return b.number("wildlife.air.band_below", 10);
    }

    public double airBandAbove() {
        return b.number("wildlife.air.band_above", 20);
    }

    /** Void Sea island base height (per region ID name, proposed value). */
    public double airBaseY(String regionName) {
        return b.number(
                "wildlife.air.base_y." + regionName, b.number("wildlife.air.base_y.default", 64));
    }

    /** Sine-wave bobbing amplitude (blocks/second) and period (seconds). */
    public double airBobSpeed() {
        return b.number("wildlife.air.bob_speed", 0.35);
    }

    public double airBobPeriod() {
        return b.number("wildlife.air.bob_period_s", 4);
    }

    /** Height range for building the medium grid (per content world name, proposed value). */
    public int gridMinY(String world) {
        return b.integer(
                "wildlife.grid." + world + ".min_y", b.integer("wildlife.grid.default.min_y", 0));
    }

    public int gridMaxY(String world) {
        return b.integer(
                "wildlife.grid." + world + ".max_y", b.integer("wildlife.grid.default.max_y", 96));
    }

    /** Chunk snapshots to take per tick (limits main-thread copy cost). */
    public int gridChunksPerTick() {
        return b.integer("wildlife.grid.chunks_per_tick", 4);
    }

    /** Fixed brightness (so species in lava aren't dark). Per medium; −1 means not fixed. */
    public int fixedLight(Medium medium) {
        int fallback = medium == Medium.LAVA ? 15 : -1;
        return b.integer("wildlife.light." + medium.key(), fallback);
    }

    public String worldAlias(String world) {
        return b.string("world_alias." + world).orElse(world);
    }
}
