package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigHit;
import wiki.creeper.harbor.core.api.RigInstance;

/** 보스 피해: 리그 부위 클릭·작살 적중 판정과 반격 페이즈의 약점 노출 주기. 메인 스레드 전용. */
final class HarpoonCombat {

    // 공용 보스전 소리 ('15. 사운드' 4.3·4.4)
    private static final String HARPOON_HIT = "harbor:boss.harpoon_hit";

    private final Encounter e;
    private double weakTimer;
    private boolean weakExposed;
    private long weakUntil = -1;

    HarpoonCombat(Encounter e) {
        this.e = e;
    }

    boolean weakExposed() {
        return weakExposed;
    }

    // ------------------------------------------------------------------ 약점 노출

    void resetWeakTimer() {
        weakTimer = 0;
    }

    void tickWeak(long now, boolean anchorSafe) {
        var bb = e.bb;
        BossDef.PhaseDef counter = e.def.phase(BossDef.PhaseKind.COUNTER).orElseThrow();
        int interval =
                counter.weakIntervalTicks() > 0
                        ? counter.weakIntervalTicks()
                        : bb.weakIntervalTicks();
        int length = counter.weakTicks() > 0 ? counter.weakTicks() : bb.weakTicks();
        if (weakExposed) {
            if (now >= weakUntil) {
                setWeak(false);
                weakTimer = 0;
            }
            return;
        }
        weakTimer += anchorSafe ? bb.weakSafeSpeedup() : 1.0;
        if (weakTimer >= interval) {
            weakUntil = now + length;
            setWeak(true);
            for (Member m : e.members.values()) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null && m.present()) {
                    e.mgr.api()
                            .ui()
                            .title(
                                    p,
                                    Component.empty(),
                                    e.msg("boss.weak.exposed"),
                                    Duration.ZERO,
                                    Duration.ofMillis(length * 50L),
                                    Duration.ofMillis(200));
                }
            }
        }
    }

    void setWeak(boolean on) {
        weakExposed = on;
        RigInstance r = e.bossRig.instance();
        if (r == null) {
            return;
        }
        for (BossDef.Part part : e.def.parts()) {
            if (part.weakpoint()) {
                r.setPartGlow(part.bone(), on);
            }
        }
    }

    // ------------------------------------------------------------------ 피해

    /** 리그 부위 클릭 (Core RigHit). 좌클릭 + 작살 = 찌르기, 우클릭 = 월드 보스 줄 걸기. */
    void onRigHit(RigHit hit) {
        Player p = hit.player();
        Member m = e.members.get(p.getUniqueId());
        if (hit.attack()) {
            Optional<ItemDef> harpoon = e.mgr.items().harpoon(p.getInventory().getItemInMainHand());
            if (harpoon.isEmpty()) {
                return;
            }
            if (m == null) {
                m = e.worldBoss.join(p, BossRole.HARPOONER);
            }
            if (m == null) {
                return;
            }
            long now = e.mgr.now();
            if (now - m.lastStab < e.bb.stabCooldownTicks()) {
                return;
            }
            m.lastStab = now;
            hit(p, m, harpoon.get(), hit.bone(), e.bb.stabMult());
            return;
        }
        // 우클릭: 월드 보스는 누구나 줄을 걸 수 있다 (인원 상한까지)
        if (e.def.isWorld()
                && (e.fight.state() == EncounterState.HOOK
                        || e.fight.state() == EncounterState.COUNTER)) {
            Member w = m == null ? e.worldBoss.join(p, BossRole.ANCHOR) : m;
            if (w == null || w.session != null) {
                return;
            }
            long anchors = e.members.values().stream().filter(x -> x.session != null).count();
            if (anchors >= e.bb.worldMaxAnchors()) {
                e.send(p.getUniqueId(), "boss.world.anchors_full");
                return;
            }
            w.role = BossRole.ANCHOR;
            e.anchors.open(w);
        }
    }

    /** 작살 투척이 판정 상자에 맞음 (EncounterManager의 궤적 판정). */
    void harpoonHit(Player p, ItemDef harpoon, String bone) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null) {
            m = e.worldBoss.join(p, BossRole.HARPOONER);
        }
        if (m != null) {
            hit(p, m, harpoon, bone, 1.0);
        }
    }

    private void hit(Player p, Member m, ItemDef harpoon, String bone, double mult) {
        var bb = e.bb;
        var mgr = e.mgr;
        if (!m.present() || m.fallen(mgr.now())) {
            return;
        }
        if (e.fight.state() != EncounterState.COUNTER) {
            mgr.api().ui().actionBar(p, e.msg("boss.hit.not_hooked"));
            return;
        }
        BossDef.Part part = e.def.partByBone(bone).orElse(null);
        double partMult;
        boolean weak = false;
        if (part != null && part.weakpoint() && weakExposed) {
            partMult = bb.weakMult() * (1 + BossItems.stat(harpoon, BossItems.STAT_WEAK_BONUS, 0));
            weak = true;
        } else if (part != null && part.armor()) {
            partMult = bb.armorMult();
        } else {
            partMult = bb.normalMult();
        }
        double base = BossItems.stat(harpoon, BossItems.STAT_HARPOON, bb.harpoonBaseDamage());
        double modifier = mgr.api().modifiers().apply(p.getUniqueId(), Encounter.BOSS_DAMAGE, 1.0);
        double amount = base * partMult * modifier * mult;
        List<Fight.Transition> out = new ArrayList<>();
        double dealt = e.fight.damage(amount, out);
        m.damage += dealt;
        m.activity(mgr.now());
        e.worldBoss.activity(m.uuid, mgr.now());
        e.anchors.mirror();
        mgr.api()
                .ui()
                .actionBar(
                        p,
                        e.msg(
                                weak
                                        ? "boss.hit.weak"
                                        : part != null && part.armor()
                                                ? "boss.hit.armor"
                                                : "boss.hit.normal",
                                Placeholder.unparsed("damage", String.valueOf(Math.round(dealt)))));
        Location fx = BossItems.loc(p);
        RigInstance r = e.bossRig.instance();
        if (r != null) {
            fx = r.location();
        }
        e.world.spawnParticle(weak ? Particle.CRIT : Particle.SPLASH, fx, 12, 0.6, 0.6, 0.6, 0.1);
        if (weak) {
            // 약점 적중 (공용): 맞은 자리에서, 멀리서 던진 작살수에게도 들리게
            HarborSounds.playAt(fx, HARPOON_HIT, HarborSounds.Options.DEFAULT, List.of(p));
        }
        e.handle(out);
    }
}
