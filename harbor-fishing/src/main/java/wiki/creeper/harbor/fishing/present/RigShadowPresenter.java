package wiki.creeper.harbor.fishing.present;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Core RigInstance로 그리는 그림자 (docs 20 3.4, '16. 플러그인 설계' 5.10). 어종 리그 {@code rig/fish/<어종 이름>}이 있으면
 * 그것, 없으면 등급 프리셋의 shadow_rig(예: rig/fish/shadow_small)를 캐스터에게만 띄운다. 크기는 프리셋 shadow_scale(전설은 배보다
 * 크게). 동작은 대기 swim, 입질 bite, 점프 leap(한 번 재생 후 swim)이고 리그에 없는 동작은 건너뛴다. 둘 다 없으면 {@link
 * DisplayShadowPresenter}로 대신한다. 그림자 감별(feature_shadow_grade)은 body 부위 발광으로 표시한다 (색은 리그
 * glow_color).
 */
public final class RigShadowPresenter implements ShadowPresenter {

    private static final String SWIM = "swim";
    private static final String BITE = "bite";
    private static final String LEAP = "leap";
    private static final String GLOW_BONE = "body";
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

    /** 이 어종의 그림자 리그 (어종 리그 → 등급 기본). */
    Optional<RigId> rigFor(FishDef fish, FishingPresets.GradePreset preset) {
        RigId own =
                new RigId(
                        new HarborId(
                                fish.id().id().namespace(), "rig/fish/" + fish.id().id().name()));
        if (rigs.ids().contains(own)) {
            return Optional.of(own);
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
        RigInstance instance =
                rigs.spawn(
                        rig.get(),
                        at,
                        RigOptions.privateTo(viewer)
                                .withScale(Math.max(0.1, preset.shadowScale()))
                                .withAnimation(SWIM)
                                .withPhase(-1));
        if (gradeGlow) {
            instance.setPartGlow(GLOW_BONE, true);
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
                // 리그에 점프 동작이 없으면 루트를 올렸다 내린다
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
