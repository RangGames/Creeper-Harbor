package wiki.creeper.harbor.boss.content;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Boss definition (bosses/*.yml, docs 20 10.3). Empty values (0 or −1) take the type default from
 * balance.yml {@code boss.types.<type>}. A running encounter keeps the definition from its start
 * (reloads do not change it, 10.6).
 *
 * <pre>
 * - id: boss/swamp_catfish
 *   name: "&lt;dark_green&gt;Lord of the Swamp 'Gnome'"
 *   type: intro
 *   goal: capture
 *   region: region/harbor
 *   arena: {mode: field, world: harbor_main, slots: [{center: [180, 62, -220], radius: 20}],
 *           deck: {spawn: [0, 1, 0], boss: [0, -1, -12], wheel: [0, 1, 3], repair: [[-2, 1, 0]]}}
 *   party: {min: 1, max: 2, base: 1}
 *   hp: {base: 2000}
 *   rig: rig/boss/gnome
 *   parts: [{id: head, bone: head, weakpoint: true}, {id: tail, bone: tail_2, armor: true}]
 *   hull: {max: 100, repair_per_action: 10}
 *   phases: [{id: hook}, {id: counter, patterns: [mud_spit]}, {id: final_tug, enter: {hp_below: 0.2}}]
 *   patterns: [{id: mud_spit, telegraph: {shape: circle, size: 4}, effect: {hull_damage: 8}}]
 *   fish: fish/swamp_catfish
 *   loot: {drops: [{item: item/material/boss_scale, chance: 1.0, count: 2}], gold: {min: 300, max: 300}}
 *   summon: {item: item/boss/summon_swamp_catfish, cooldown: 600}
 * </pre>
 *
 * @param story story fight (proposed: no loot, records or weekly count; 10.1 Lord of the Trench
 *     calming fight)
 * @param timeLimitSeconds 0 means the type default
 * @param rigScale rig size multiplier (proposed)
 * @param fish linked boss fish (guaranteed catch; collection only if it is a release_only fish)
 */
public record BossDef(
        BossId id,
        String name,
        BossType type,
        Goal goal,
        RegionId region,
        boolean story,
        Arena arena,
        PartyRule party,
        Hp hp,
        int timeLimitSeconds,
        RigId rig,
        double rigScale,
        List<Part> parts,
        Hull hull,
        List<PhaseDef> phases,
        List<PatternDef> patterns,
        @Nullable FishId fish,
        Loot loot,
        @Nullable Summon summon,
        Presentation presentation) {

    /** Hit part cap (proposed value, docs 20 10.3). */
    public static final int MAX_HIT_PARTS = 8;

    /** Rig part cap ('8. System details' chapter 12). */
    public static final int MAX_RIG_PARTS = 30;

    /**
     * Goal: capture or calm (Lord of the Trench and Verdigris Thunderfish use a rage gauge instead
     * of HP).
     */
    public enum Goal implements Keyed {
        CAPTURE,
        CALM;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Arena mode (docs 20 10.4). */
    public enum ArenaMode implements Keyed {
        /**
         * Fixed deck slot inside a main server region. The boss is visible to party members only.
         */
        FIELD,
        /** Arena cell on an instance server (raids). */
        INSTANCE,
        /** Fixed location on the main server, visible to everyone (world boss). */
        WORLD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** One arena slot. center is in world coordinates, radius is the boundary radius (blocks). */
    public record Slot(Vec3 center, double radius) {}

    /**
     * Fixed deck structure (coordinates relative to the slot center).
     *
     * @param spawn deck position where party members stand
     * @param boss boss spawn position
     * @param wheel helm Interaction position (captain dodging; no dodge input if absent)
     * @param repair damaged spots (mechanic repairs)
     */
    public record Deck(Vec3 spawn, Vec3 boss, @Nullable Vec3 wheel, List<Vec3> repair) {}

    public record Arena(ArenaMode mode, String world, List<Slot> slots, Deck deck) {}

    /**
     * @param base balance reference party size (reference value for HP scaling)
     */
    public record PartyRule(int min, int max, int base) {}

    /**
     * @param perPlayerRate HP increase per player. Negative means the type default
     */
    public record Hp(double base, double perPlayerRate) {}

    /**
     * Hit part. The hitbox size comes from the rig bone's hitbox (so the rig and boss definition
     * stay in sync).
     *
     * @param weakpoint part that glows during weak point exposure and takes a 3.0 damage multiplier
     * @param armor hard part (damage multiplier 0.2)
     */
    public record Part(String id, String bone, boolean weakpoint, boolean armor) {}

    /**
     * Ship durability.
     *
     * @param repairPerAction amount restored per repair kit use
     */
    public record Hull(int max, int repairPerAction) {}

    /** Phase kind (docs 20 10.4 state machine). */
    public enum PhaseKind implements Keyed {
        HOOK,
        COUNTER,
        FINAL_TUG;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Final tug-of-war settings. 0 means the balance.yml boss.qte default.
     *
     * @param rounds number of rounds (default 3)
     * @param inputs inputs per round (default 4)
     * @param windowTicks time limit per input (default 20 ticks = 1.0s)
     * @param pass round success ratio (default 0.6)
     */
    public record Qte(int rounds, int inputs, int windowTicks, double pass) {
        public static final Qte DEFAULT = new Qte(0, 0, 0, 0);
    }

    /**
     * One phase.
     *
     * @param hpBelow entry condition (final_tug: HP ratio at or below this value). 0 means no
     *     condition
     * @param patterns pattern IDs chosen in this phase
     * @param patternGapTicks minimum gap between patterns (0 means the type default)
     * @param weakIntervalTicks weak point exposure interval (counter, 0 means the default)
     * @param weakTicks weak point exposure duration (counter, 0 means the default)
     * @param hookTicks safe-zone hold time to fill the hook gauge from 0 to 100% (hook, 0 means the
     *     default)
     */
    public record PhaseDef(
            PhaseKind kind,
            double hpBelow,
            List<String> patterns,
            int patternGapTicks,
            int weakIntervalTicks,
            int weakTicks,
            int hookTicks,
            Qte qte) {}

    /** Telegraph area shape (docs 20 10.3). */
    public enum Shape implements Keyed {
        CIRCLE,
        LINE,
        CONE,
        RING;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Telegraph area center (proposed). */
    public enum Anchor implements Keyed {
        /** At the feet of a random party member. */
        MEMBER,
        /** Deck center. */
        DECK,
        /** From the boss position toward the deck (line/cone direction). */
        BOSS;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Telegraph. size is the circle/cone radius, line width, or ring outer radius.
     *
     * @param length line length (blocks)
     * @param angle full cone angle (degrees)
     * @param inner ring inner radius
     * @param ticks telegraph time (0 means the type default; ping compensation is added at runtime)
     */
    public record Telegraph(
            Shape shape,
            double size,
            double length,
            double angle,
            double inner,
            int ticks,
            Anchor at) {}

    /** Debuff (personal effect, shown on the action bar). */
    public enum Debuff implements Keyed {
        NONE,
        /** Mud splash (blocks vision). */
        BLIND,
        /** Ink (blindness). */
        DARKNESS,
        SLOW,
        /** Gravity reversal (levitation). */
        LEVITATE,
        /**
         * Control reversal (Void Nebula Fish nebula burst). QTE left/right and forward/back are
         * flipped.
         */
        INVERT;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Pattern effect. Knockback for players in range and ship durability loss; debuffs are personal
     * effects.
     *
     * @param tension value added to anchor tension (proposed, 0-1)
     */
    public record Effect(
            int hullDamage, double knockback, Debuff debuff, int debuffTicks, double tension) {}

    /**
     * One pattern.
     *
     * @param name name shown on the action bar (MiniMessage)
     * @param animation one-shot rig animation (not played if absent)
     * @param charge charge pattern (dodged when the captain steers the opposite way at the helm)
     */
    public record PatternDef(
            String id,
            String name,
            @Nullable String animation,
            Telegraph telegraph,
            Effect effect,
            int cooldownTicks,
            int weight,
            boolean charge) {}

    /**
     * One drop.
     *
     * @param pity pity target (guaranteed after missing it loot.pity_after times in a row)
     */
    public record Drop(ItemId item, double chance, int countMin, int countMax, boolean pity) {}

    /**
     * Loot.
     *
     * @param goldMin clear gold (−1 means the type default)
     * @param topBonus extra rewards for the top 20% by contribution
     * @param pityAfter raid pity (0 means none)
     * @param participation participation reward on failure (1 boss scale)
     * @param title clear title (null if none)
     */
    public record Loot(
            List<Drop> drops,
            long goldMin,
            long goldMax,
            List<Drop> topBonus,
            int pityAfter,
            @Nullable Drop participation,
            @Nullable HarborId title) {}

    /**
     * Summon bait.
     *
     * @param cooldownSeconds resummon cooldown for the same party leader
     */
    public record Summon(ItemId item, int cooldownSeconds) {}

    /**
     * Presentation.
     *
     * @param intro one-shot rig animation on spawn
     * @param outro cutscene ID after a clear (only when the NPC module is present)
     * @param music sound key played at the start
     * @param roar roar sound key (played at the boss position on spawn, counter and final
     *     tug-of-war; '15. Sound' 4.3, proposed)
     * @param announce server announcement for kills and new records
     */
    public record Presentation(
            @Nullable String intro,
            @Nullable HarborId outro,
            @Nullable String music,
            @Nullable String roar,
            boolean announce) {}

    public static final ContentType.Entries<BossDef> TYPE =
            new ContentType.Entries<>(
                    "boss",
                    List.of("bosses/*.yml"),
                    BossId.PREFIX,
                    BossDef::parse,
                    b -> b.id().id());

    // ------------------------------------------------------------------ Lookup

    public Optional<PhaseDef> phase(PhaseKind kind) {
        return phases.stream().filter(p -> p.kind() == kind).findFirst();
    }

    public Optional<PatternDef> pattern(String patternId) {
        return patterns.stream().filter(p -> p.id().equals(patternId)).findFirst();
    }

    public Optional<Part> partByBone(String bone) {
        return parts.stream().filter(p -> p.bone().equals(bone)).findFirst();
    }

    /**
     * HP ratio for entering the final tug-of-war (if absent, definition checks reject it rather
     * than using a 0.2 proposed value).
     */
    public double finalTugBelow() {
        return phase(PhaseKind.FINAL_TUG).map(PhaseDef::hpBelow).orElse(0.0);
    }

    public boolean isWorld() {
        return arena.mode() == ArenaMode.WORLD;
    }

    // ------------------------------------------------------------------ Parsing

    static BossDef parse(YamlNode node, ParseContext ctx) {
        return BossDefParser.parse(node, ctx);
    }
}
