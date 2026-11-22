package wiki.creeper.harbor.npc.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.ReputationTier;

/**
 * The reputation section (docs 20 5.3) and npc section (proposed) of balance.yml. Missing values
 * fall back to the proposed values in docs 20 5.4. All numbers are to be finalized with CBT
 * metrics.
 */
public record NpcBalance(Balance balance) {

    // ------------------------------------------------------------------ reputation

    /**
     * Tier table (1-based, ascending cumulative reputation). balance.yml {@code
     * reputation.tiers.<tier>: {points, name}}.
     */
    public List<ReputationTier> reputationTiers() {
        List<ReputationTier> tiers = new ArrayList<>();
        for (String key : balance.keys("reputation.tiers")) {
            int tier;
            try {
                tier = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            String base = "reputation.tiers." + key;
            tiers.add(
                    new ReputationTier(
                            tier,
                            balance.integer(base + ".points", 0),
                            balance.string(base + ".name").orElse(String.valueOf(tier))));
        }
        if (tiers.isEmpty()) {
            // docs 20 5.4 reputation table (proposed values)
            tiers.add(new ReputationTier(1, 0, "이방인"));
            tiers.add(new ReputationTier(2, 300, "이웃"));
            tiers.add(new ReputationTier(3, 1_000, "단골"));
            tiers.add(new ReputationTier(4, 2_000, "항구의 일원"));
            tiers.add(new ReputationTier(5, 3_500, "항구의 기둥"));
        }
        tiers.sort(Comparator.comparingInt(ReputationTier::tier));
        return List.copyOf(tiers);
    }

    /** Default reputation per source (docs 20 5.4 proposed values). */
    public int sourceValue(ReputationSource source) {
        int fallback =
                switch (source) {
                    case MAIN_STORY -> 150;
                    case REGION_QUEST -> 40;
                    case DAILY_REQUEST -> 10;
                    case WEEKLY_REQUEST -> 30;
                    case COLLECT_REQUEST -> 20;
                    case BOSS_FIRST_KILL -> 50;
                    case WORLD_BOSS -> 500;
                    case RELEASE -> 3;
                    case ADMIN, OTHER -> 0;
                };
        return balance.integer("reputation.sources." + source.key(), fallback);
    }

    /** Daily cap per source (0 means none). Release 30 (proposed value). */
    public int dailyCap(ReputationSource source) {
        int fallback = source == ReputationSource.RELEASE ? 30 : 0;
        return balance.integer("reputation.daily_caps." + source.key(), fallback);
    }

    /** Number of lines in the reputation command's recent history. */
    public int recentSize() {
        return balance.integer("reputation.recent_size", 10);
    }

    // ------------------------------------------------------------------ affinity

    /**
     * Default level table (when npcs.yml has no affinity_levels). {@code
     * npc.affinity.levels.<level>: {points, name, discount, unlocks}}.
     */
    public List<AffinityLevel> affinityLevels() {
        List<AffinityLevel> levels = new ArrayList<>();
        List<String> keys = new ArrayList<>(balance.keys("npc.affinity.levels"));
        keys.sort(Comparator.comparingInt(NpcBalance::intOrMax));
        for (String key : keys) {
            String base = "npc.affinity.levels." + key;
            levels.add(
                    new AffinityLevel(
                            levels.size(),
                            balance.integer(base + ".points", 0),
                            balance.string(base + ".name").orElse(key),
                            balance.integer(base + ".discount", 0),
                            balance.string(base + ".unlocks")
                                    .map(s -> List.of(s.split(",")))
                                    .orElse(List.of())));
        }
        if (levels.isEmpty()) {
            levels.add(new AffinityLevel(0, 0, "낯섦", 0, List.of()));
            levels.add(new AffinityLevel(1, 100, "아는 사이", 0, List.of()));
            levels.add(new AffinityLevel(2, 300, "친구", 3, List.of()));
            levels.add(new AffinityLevel(3, 600, "단짝", 5, List.of()));
            levels.add(new AffinityLevel(4, 1_000, "신뢰", 8, List.of()));
        }
        return List.copyOf(levels);
    }

    /** Affinity for the first talk of the day (proposed value +2). */
    public int greetPoints() {
        return balance.integer("npc.affinity.greet", 2);
    }

    /** Affinity for items not in the gifts table (default +1). */
    public int giftDefault() {
        return balance.integer("npc.affinity.gift_default", 1);
    }

    /** Fish gift quality multiplier × (1 + bonus × (★ − 1)), proposed value 0.25. */
    public double giftQualityBonus() {
        return balance.number("npc.affinity.gift_quality_bonus", 0.25);
    }

    /** Gifts per NPC per day (1). */
    public int giftsPerDay() {
        return balance.integer("npc.affinity.gifts_per_day", 1);
    }

    public int reactionLove() {
        return balance.integer("npc.affinity.reactions.love", 15);
    }

    public int reactionLike() {
        return balance.integer("npc.affinity.reactions.like", 5);
    }

    // ------------------------------------------------------------------ dialog and entities

    /** Dialog session lifetime (5 minutes). */
    public int dialogTimeoutSeconds() {
        return balance.integer("npc.dialog.timeout_seconds", 300);
    }

    /** Maximum distance to the NPC when clicking a choice (6 blocks). */
    public double optionDistance() {
        return balance.number("npc.dialog.option_distance", 6);
    }

    /** Ignore repeated clicks from the same player (10 ticks). */
    public int clickCooldownTicks() {
        return balance.integer("npc.dialog.click_cooldown_ticks", 10);
    }

    // ------------------------------------------------------------------ dialog style (proposed,
    // all proposed values)

    /** Style when the NPC has no dialog_style (dialog). */
    public DialogStyle defaultStyle() {
        return balance.string("npc.dialog.default_style")
                .flatMap(DialogStyle::parse)
                .orElse(DialogStyle.DIALOG);
    }

    /** Non-DIALOG sessions: distance and speech bubble check interval (10 ticks). */
    public int watchTicks() {
        return Math.max(2, balance.integer("npc.dialog.watch_ticks", 10));
    }

    /** Speech bubble height (from the NPC's feet, above the name and marker). */
    public double hologramOffset() {
        return balance.number("npc.dialog.hologram.offset", 2.9);
    }

    /** Speech bubble line width (pixels, TextDisplay line width). */
    public int hologramLineWidth() {
        return Math.max(40, balance.integer("npc.dialog.hologram.line_width", 180));
    }

    /** How long a one-liner (say) bubble or title stays up (seconds). */
    public double barkSeconds() {
        return Math.max(1, balance.number("npc.dialog.hologram.bark_seconds", 4));
    }

    /** Typewriter effect: characters per tick (0 means all at once). */
    public int typewriterCharsPerTick() {
        return Math.max(0, balance.integer("npc.dialog.hologram.typewriter_chars_per_tick", 2));
    }

    /** Speech bubble view distance multiplier (TextDisplay view range, 1 = 64 blocks). */
    public float hologramViewRange() {
        return (float) balance.number("npc.dialog.hologram.view_range", 0.5);
    }

    /** Speech bubble background opacity (0-255). */
    public int hologramBackgroundAlpha() {
        return Math.clamp(balance.integer("npc.dialog.hologram.background_alpha", 96), 0, 255);
    }

    /** How long the subtitle stays up in TITLE style (seconds). If auto is set, that value. */
    public double titleStaySeconds() {
        return Math.max(1, balance.number("npc.dialog.title.stay_seconds", 8));
    }

    /**
     * If the subtitle exceeds this many characters, also send it to chat (it may appear cut off).
     */
    public int titleMaxChars() {
        return Math.max(8, balance.integer("npc.dialog.title.max_chars", 40));
    }

    /** ACTIONBAR style: interval for resending so the action bar does not fade (40 ticks). */
    public int actionBarRefreshTicks() {
        return Math.max(10, balance.integer("npc.dialog.actionbar.refresh_ticks", 40));
    }

    public int lookIntervalTicks() {
        return Math.max(1, balance.integer("npc.entity.look_interval_ticks", 4));
    }

    public double interactionWidth() {
        return balance.number("npc.entity.interaction_width", 0.8);
    }

    public double interactionHeight() {
        return balance.number("npc.entity.interaction_height", 2.0);
    }

    /** Name Text Display height (from the feet). */
    public double nameOffset() {
        return balance.number("npc.entity.name_offset", 2.15);
    }

    public double markerOffset() {
        return balance.number("npc.entity.marker_offset", 2.5);
    }

    // ------------------------------------------------------------------ wanderers

    public int wandererSlotHours() {
        return balance.integer("npc.wanderer.slot_hours", 3);
    }

    /** Conditional candidate check interval (1 minute). */
    public int wandererCheckTicks() {
        return Math.max(20, balance.integer("npc.wanderer.check_interval_ticks", 1_200));
    }

    /** Maximum time to postpone a move while a player is in dialog (60 seconds). */
    public int wandererMaxDelaySeconds() {
        return balance.integer("npc.wanderer.max_delay_seconds", 60);
    }

    // ------------------------------------------------------------------ cutscenes

    /** Maximum time to wait for the restore info write (1 second). */
    public int cutsceneRestoreTimeoutMillis() {
        return balance.integer("npc.cutscene.restore_timeout_ms", 1_000);
    }

    /** Double-sneak interval (10 ticks). */
    public int cutsceneSkipWindowTicks() {
        return balance.integer("npc.cutscene.skip_window_ticks", 10);
    }

    /** Cutscene entity cap per player (20). */
    public int cutsceneMaxEntities() {
        return balance.integer("npc.cutscene.max_entities", 20);
    }

    /**
     * Maximum length of one camera interpolation (within the teleport_duration cap of 59 ticks).
     */
    public int cameraStepTicks() {
        return Math.clamp(balance.integer("npc.cutscene.camera_step_ticks", 40), 1, 59);
    }

    private static int intOrMax(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
