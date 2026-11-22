package wiki.creeper.harbor.fishing.present;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Shadow drawn with Core RigInstance (docs 20 3.4, "16. Plugin design" 5.10). Shows the species rig
 * {@code rig/fish/<model name>} (FishRigs) if present, otherwise the grade preset's shadow_rig
 * (e.g. rig/fish/shadow_small), to the caster only. Size is the preset shadow_scale (legendaries
 * are bigger than the boat). Animations are swim while waiting, bite on the bite and leap for the
 * jump (played once, then swim); animations missing from the rig are skipped. If neither rig
 * exists, {@link DisplayShadowPresenter} is used instead. Shadow identification
 * (feature_shadow_grade) is shown by making all parts glow (color from the rig glow_color).
 */
public final class RigShadowPresenter implements ShadowPresenter {

    private static final String SWIM = "swim";
    private static final String BITE = "bite";
    private static final String LEAP = "leap";
    private static final int JUMP_TICKS = 16;
    private static final double JUMP_HEIGHT = 1.8;

    private final RigService rigs;
    private final ShadowPresenter fallback;
    private final Tasks tasks;

    public RigShadowPresenter(RigService rigs, ShadowPresenter fallback, Tasks tasks) {
        this.rigs = rigs;
        this.fallback = fallback;
        this.tasks = tasks;
    }

    /** Shadow rig of this species (species rig → grade default). */
    Optional<RigId> rigFor(FishDef fish, FishingPresets.GradePreset preset) {
        // The species rig follows the model name (FishDef.model). Otherwise the old rule (species
        // ID name)
        RigId own = FishRigs.rigOf(fish);
        if (rigs.ids().contains(own)) {
            return Optional.of(own);
        }
        RigId byId = FishRigs.byId(fish);
        if (rigs.ids().contains(byId)) {
            return Optional.of(byId);
        }
        String shadow = preset.shadowRig();
        if (shadow == null) {
            return Optional.empty();
        }
        try {
            RigId id = RigId.parse(shadow);
            return rigs.ids().contains(id) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public Shadow spawn(
            Player viewer,
            Location at,
            FishDef fish,
            FishingPresets.GradePreset preset,
            boolean gradeGlow) {
        Optional<RigId> rig = rigFor(fish, preset);
        if (rig.isEmpty()) {
            return fallback.spawn(viewer, at, fish, preset, gradeGlow);
        }
        // shadow_scale is treated as the shadow body length (blocks). Rigs with a body length
        // (species rigs) are fitted to it; otherwise the multiplier is used as is
        double size = Math.max(0.1, preset.shadowScale());
        double scale =
                rigs.definition(rig.get())
                        .filter(d -> d.length() > 0)
                        .map(d -> FishRigs.fitScale(d, size))
                        .orElse(size);
        RigInstance instance =
                rigs.spawn(
                        rig.get(),
                        at,
                        RigOptions.privateTo(viewer)
                                .withScale(scale)
                                .withAnimation(SWIM)
                                .withPhase(-1));
        if (gradeGlow) {
            instance.setPartGlow(RigInstance.ALL_PARTS, true);
        }
        return new RigShadow(instance, viewer);
    }

    private final class RigShadow implements Shadow {

        private final RigInstance rig;
        private final Player viewer;

        RigShadow(RigInstance rig, Player viewer) {
            this.rig = rig;
            this.viewer = viewer;
        }

        @Override
        public Location location() {
            return rig.location();
        }

        @Override
        public void bite() {
            var _ = rig.play(BITE);
        }

        @Override
        public void moveTo(Location target, int ticks) {
            Location from = rig.location();
            double dx = target.getX() - from.getX();
            double dz = target.getZ() - from.getZ();
            if (dx * dx + dz * dz > 1e-4) {
                rig.setHeading((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
            }
            rig.moveTo(target, Math.clamp(ticks, 0, 59));
            if (BITE.equals(rig.animation()) && ticks > 4) {
                var _ = rig.play(SWIM);
            }
        }

        @Override
        public void jump(Collection<Player> extraViewers) {
            List<Player> extras = List.copyOf(extraViewers);
            for (Player p : extras) {
                if (!p.equals(viewer)) {
                    rig.addViewer(p);
                }
            }
            if (!rig.playOnce(LEAP, SWIM)) {
                // If the rig has no jump animation, raise and lower the root
                Location base = rig.location();
                rig.moveTo(base.clone().add(0, JUMP_HEIGHT, 0), JUMP_TICKS / 2);
                var _ = tasks.later(JUMP_TICKS / 2, () -> rig.moveTo(base, JUMP_TICKS / 2));
            }
            var _ =
                    tasks.later(
                            JUMP_TICKS + 4,
                            () -> {
                                for (Player p : extras) {
                                    if (!p.equals(viewer) && p.isOnline()) {
                                        rig.removeViewer(p);
                                    }
                                }
                            });
        }

        @Override
        public boolean isValid() {
            return rig.isValid();
        }

        @Override
        public void remove() {
            rig.remove();
        }
    }
}
