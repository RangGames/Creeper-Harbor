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

/** 보스 리그 (Core RigService): 등장·재생성·포효·판정 상자 목록·제거. 메인 스레드 전용. */
final class BossRig {

    private final Encounter e;
    private @Nullable RigInstance rig;

    /** 리그 판정 상자 뼈 이름 (Interaction 순서). */
    private final List<String> hitboxBones = new ArrayList<>();

    BossRig(Encounter e) {
        this.e = e;
    }

    @Nullable RigInstance instance() {
        return rig;
    }

    /** 등장 (INTRO): 리그를 띄우고 판정 상자 뼈 이름을 모은 뒤 intro 애니메이션을 한 번 재생한다. */
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

    /**
     * 포효 ('15. 사운드' 4.3): 보스 위치에서 sounds.yml 음량(3 이상)으로 내고, 들리는 범위 밖 참가자에게는 직접 보낸다.
     * presentation.roar가 없으면 생략.
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
            // 청크가 언로드되는 등으로 리그가 사라지면 같은 자리에 다시 띄운다 (청크 티켓을 걸어 두므로 드물다)
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

    /** 정리: 참조를 먼저 비우고 리그를 지운다 (onRemove가 재생성하지 않게). */
    void remove() {
        RigInstance r = rig;
        rig = null;
        if (r != null) {
            r.remove();
        }
    }

    /** 리그 판정 상자 Interaction과 뼈 이름 (작살 궤적 판정용). */
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
