package wiki.creeper.harbor.wildlife.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.GroupDef;
import wiki.creeper.harbor.wildlife.content.WildGroup;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildTemplate;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;

/**
 * Runtime values combining a species.yml entry + species definition + rig + behaviours.yml ('17-3'
 * 9.3 "defaults generated automatically from the rig/BEHAVE, designers write only overrides").
 * Immutable, so the simulation thread reads it as-is. Rebuilt when content reloads.
 *
 * @param animations animation names in the rig → length (ticks)
 * @param threatAction startle action played first when threatened (startle, ink, dart in order;
 *     only those in the rig)
 * @param biteAnimation bite animation (bite, else the special action, else null)
 * @param radius body radius (blocks)
 * @param light fixed brightness (−1 for none)
 */
public record SpeciesProfile(
        HarborId species,
        FishDef fish,
        WildTemplate template,
        Medium medium,
        WildGroup group,
        GroupDef groupDef,
        @Nullable ActionDef special,
        @Nullable ActionDef threatAction,
        Map<String, ActionDef> actions,
        Map<String, Integer> animations,
        String swimAnimation,
        String idleAnimation,
        @Nullable String biteAnimation,
        double cruiseSpeed,
        double turnRateDeg,
        double pitchLimitDeg,
        double depthMin,
        double depthMax,
        int schoolMin,
        int schoolMax,
        double threatRadius,
        double spawnWeight,
        RigId rig,
        int parts,
        double length,
        double radius,
        double scale,
        int light,
        boolean enabled) {

    /** Startle action candidates ('17-3' 9.4: startle, ink, dart). */
    static final List<String> THREAT_ORDER = List.of("startle", "ink", "dart");

    public SpeciesProfile {
        actions = Map.copyOf(actions);
        animations = Map.copyOf(animations);
    }

    public boolean hasAnimation(@Nullable String name) {
        return name != null && animations.containsKey(name);
    }

    /** Length of one play of an action (seconds). fallback if not in the rig. */
    public double animationSeconds(@Nullable String name, double fallback) {
        Integer ticks = name == null ? null : animations.get(name);
        return ticks == null ? fallback : ticks / 20.0;
    }

    /** Base speed (cruise × temperament multiplier). */
    public double baseSpeed() {
        return cruiseSpeed * (1 + groupDef.speedBase());
    }

    public boolean naturalSpawn() {
        return enabled
                && spawnWeight > 0
                && fish.grade() != wiki.creeper.harbor.common.model.Grade.LEGENDARY
                && fish.grade() != wiki.creeper.harbor.common.model.Grade.MYTHIC;
    }

    /**
     * Combines them.
     *
     * @param rigDef rig to draw (result of {@link #rigFor})
     */
    public static SpeciesProfile resolve(
            WildSpecies s, FishDef fish, RigDef rigDef, BehaviourBook book, WildlifeBalance bal) {
        Optional<ActionDef> special = book.action(s.special());
        WildGroup group =
                s.group() != null
                        ? s.group()
                        : special.map(ActionDef::group).orElse(WildGroup.NORMAL);
        GroupDef groupDef = book.group(group);

        // species rigs (rig/fish/…, converted from the resource pack) have model lengths that
        // differ from real size, so they're drawn to the species size (midpoint). Template rigs
        // (rig/wild/…) use the rig length as the body length
        boolean fishRig = rigDef.id().id().path().startsWith(FISH_RIG_PATH);
        double fit = fishRig ? FishRigs.fitScale(rigDef, sizeLength(fish)) : 1.0;
        double length =
                fishRig || rigDef.length() <= 0
                        ? sizeLength(fish) * s.scale()
                        : rigDef.length() * rigDef.scale() * s.scale();
        double cruise =
                s.cruiseSpeed() > 0
                        ? s.cruiseSpeed()
                        : Math.clamp(
                                length * bal.cruisePerLength(), bal.cruiseMin(), bal.cruiseMax());
        double turn = s.turnRate() > 0 ? s.turnRate() : bal.turnRate(s.template());
        boolean floor = s.medium() == Medium.FLOOR;
        int schoolMin;
        int schoolMax;
        if (floor) {
            schoolMin = 1;
            schoolMax = 1;
        } else if (s.schoolMin() > 0) {
            schoolMin = s.schoolMin();
            schoolMax = s.schoolMax();
        } else if (length < bal.smallLength()) {
            schoolMin = bal.smallSchoolMin();
            schoolMax = Math.max(schoolMin, bal.smallSchoolMax());
        } else {
            schoolMin = bal.largeSchoolMin();
            schoolMax = Math.max(schoolMin, bal.largeSchoolMax());
        }

        Map<String, Integer> anims = new HashMap<>();
        rigDef.animations().forEach((name, a) -> anims.put(name, a.length()));
        String swim =
                rigDef.defaultAnimation() != null
                        ? rigDef.defaultAnimation()
                        : anims.containsKey("swim") ? "swim" : "";
        String idle = anims.containsKey("idle") ? "idle" : swim;

        ActionDef threat = null;
        if (special.isPresent()
                && special.get().threat()
                && anims.containsKey(special.get().name())) {
            threat = special.get();
        } else {
            for (String name : THREAT_ORDER) {
                Optional<ActionDef> a = book.action(name);
                if (a.isPresent() && anims.containsKey(name)) {
                    threat = a.get();
                    break;
                }
            }
        }
        String bite =
                anims.containsKey("bite")
                        ? "bite"
                        : special.map(ActionDef::name).filter(anims::containsKey).orElse(null);

        return new SpeciesProfile(
                s.id(),
                fish,
                s.template(),
                s.medium(),
                group,
                groupDef,
                special.orElse(null),
                threat,
                book.actions(),
                anims,
                swim,
                idle,
                bite,
                cruise,
                turn,
                bal.pitchLimit(s.template()),
                s.depthMin(),
                s.depthMax(),
                schoolMin,
                schoolMax,
                s.threatRadius() > 0 ? s.threatRadius() : groupDef.threatRadius(),
                s.spawnWeight(),
                rigDef.id(),
                rigDef.partCount(),
                length,
                Math.max(0.25, length / 2),
                s.scale() * fit,
                bal.fixedLight(s.medium()),
                s.enabled());
    }

    /** Species size (cm) midpoint in blocks (1m). */
    static double sizeLength(FishDef fish) {
        return FishRigs.naturalLength(fish);
    }

    private static final String FISH_RIG_PATH = "rig/fish/";

    /**
     * Picks the rig to draw: species.yml rig → species rig rig/fish/&lt;model name&gt;
     * (FishDef.model, same rule as Fishing/Collection, else rig/fish/&lt;species name&gt;) →
     * rig/wild/&lt;template&gt;.
     */
    public static Optional<RigId> rigFor(WildSpecies s, FishDef fish, Set<RigId> known) {
        RigId explicit = s.rig();
        if (explicit != null) {
            return known.contains(explicit) ? Optional.of(explicit) : Optional.empty();
        }
        RigId own = FishRigs.rigOf(fish);
        if (known.contains(own)) {
            return Optional.of(own);
        }
        RigId byId = FishRigs.byId(fish);
        if (known.contains(byId)) {
            return Optional.of(byId);
        }
        RigId template =
                new RigId(
                        new HarborId(HarborId.DEFAULT_NAMESPACE, "rig/wild/" + s.template().key()));
        return known.contains(template) ? Optional.of(template) : Optional.empty();
    }
}
