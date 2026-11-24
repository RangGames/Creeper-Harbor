package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;

/** Boss rig (Core RigService): spawn, respawn, roar, hitbox list and removal. Main thread only. */
final class BossRig {

    private final Encounter e;
    private @Nullable RigInstance rig;

    /** Rig hitbox bone names (Interaction order). */
    private final List<String> hitboxBones = new ArrayList<>();

    BossRig(Encounter e) {
        this.e = e;
    }

    @Nullable RigInstance instance() {
        return rig;
    }

    /**
     * Spawn (INTRO): spawns the rig, collects the hitbox bone names, then plays the intro animation
     * once.
     */
    void spawn() {
        var def = e.def;
        Location at = e.bossLocation();
        RigOptions options =
                def.isWorld() ? RigOptions.publicRig() : RigOptions.privateTo(e.onlinePlayers());
        options = options.withScale(def.rigScale()).withHitboxes(e.combat::onRigHit);
        try {
            RigInstance r = e.mgr.api().rigs().spawn(def.rig(), at, options);
            r.onRemove(reason -> rigRemoved());
            rig = r;
            RigDef rigDef = e.mgr.api().rigs().definition(def.rig()).orElse(null);
            hitboxBones.clear();
            if (rigDef != null) {
                for (RigDef.Bone bone : rigDef.bones()) {
                    if (bone.hitbox() != null) {
                        hitboxBones.add(bone.name());
                    }
                }
            }
            String intro = def.presentation().intro();
            if (intro != null) {
                r.playOnce(intro, null);
            }
        } catch (IllegalArgumentException ex) {
            e.mgr.log().warn("보스 리그를 띄우지 못했습니다 {}: {}", def.rig(), String.valueOf(ex.getMessage()));
        }
    }

    /** Current boss position (rig if spawned, else the arena boss point). */
    Location location() {
        RigInstance r = rig;
        return r != null ? r.location() : e.bossLocation();
    }

    /**
     * Roar ('15. Sound' 4.3): played at the boss position at the sounds.yml volume (3 or more), and
     * sent directly to participants outside hearing range. Skipped if presentation.roar is absent.
     */
    void roar() {
        RigInstance r = rig;
        Location at = r != null ? r.location() : e.bossLocation();
        HarborSounds.playAt(
                at,
                e.def.presentation().roar(),
                HarborSounds.Options.DEFAULT.withSource(Sound.Source.HOSTILE),
                e.onlinePlayers());
    }

    private void rigRemoved() {
        rig = null;
        if (!e.finished() && e.fight.state().fighting()) {
            // If the rig disappears (e.g. chunk unload), respawn it in place (rare, since chunk
            // tickets are held)
            var _ = Bukkit.getScheduler().runTask(e.mgr.plugin(), this::respawn);
        }
    }

    private void respawn() {
        if (e.finished() || rig != null) {
            return;
        }
        var def = e.def;
        RigOptions options =
                def.isWorld() ? RigOptions.publicRig() : RigOptions.privateTo(e.onlinePlayers());
        try {
            RigInstance r =
                    e.mgr.api()
                            .rigs()
                            .spawn(
                                    def.rig(),
                                    e.bossLocation(),
                                    options.withScale(def.rigScale())
                                            .withHitboxes(e.combat::onRigHit));
            r.onRemove(reason -> rigRemoved());
            rig = r;
        } catch (IllegalArgumentException ex) {
            e.mgr.log().warn("보스 리그 재생성 실패 {}", def.rig());
        }
    }

    /** Cleanup: clears the reference first, then removes the rig (so onRemove does not respawn). */
    void remove() {
        RigInstance r = rig;
        rig = null;
        if (r != null) {
            r.remove();
        }
    }

    /** Rig hitbox Interactions and bone names (for harpoon trajectory hits). */
    List<Map.Entry<Interaction, String>> hitboxes() {
        RigInstance r = rig;
        if (r == null) {
            return List.of();
        }
        List<Map.Entry<Interaction, String>> out = new ArrayList<>();
        int i = 0;
        for (Entity en : r.entities()) {
            if (en instanceof Interaction box && i < hitboxBones.size()) {
                out.add(Map.entry(box, hitboxBones.get(i++)));
            }
        }
        return out;
    }
}
