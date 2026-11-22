package wiki.creeper.harbor.boss.encounter;

import net.kyori.adventure.sound.Sound;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;

/**
 * Reactions to {@link Fight} state transitions of one encounter: titles, anchor lines, roars, weak
 * point and pattern resets, QTE arming, the intro spawn and the hand-off to EncounterManager on
 * end. Main thread only.
 */
final class PhaseTransitions {

    private final Encounter e;

    PhaseTransitions(Encounter e) {
        this.e = e;
    }

    void onTransition(Fight.Transition t) {
        e.mgr.firePhase(e, t);
        switch (t.to()) {
            case INTRO -> onIntro();
            case HOOK -> {
                e.hud.titleAll("boss.phase.hook.title", "boss.phase.hook.subtitle");
                for (Member m : e.members.values()) {
                    if (m.role == BossRole.ANCHOR && m.present()) {
                        e.anchors.open(m);
                    }
                }
            }
            case COUNTER -> {
                if ("tug_failed".equals(t.cause())) {
                    e.hud.titleAll("boss.phase.recover.title", "boss.phase.recover.subtitle");
                    e.qte.end();
                    for (Member m : e.members.values()) {
                        if (m.role == BossRole.ANCHOR && m.present()) {
                            e.anchors.open(m);
                        }
                    }
                } else {
                    e.hud.titleAll("boss.phase.counter.title", "boss.phase.counter.subtitle");
                }
                e.bossRig.roar();
                e.combat.resetWeakTimer();
                e.patterns.delay(e.mgr.now() + 40);
                RigInstance r = e.bossRig.instance();
                if (r != null) {
                    r.play("idle");
                }
            }
            case FINAL_TUG -> {
                if ("tug_retry".equals(t.cause())) {
                    e.broadcast("boss.qte.retry");
                } else {
                    e.hud.titleAll("boss.phase.tug.title", "boss.phase.tug.subtitle");
                    e.bossRig.roar();
                }
                e.patterns.clear();
                e.combat.setWeak(false);
                for (Member m : e.members.values()) {
                    e.anchors.close(m);
                }
                e.qte.arm(e.mgr.now());
            }
            case CLEARED, FAILED, ABORTED -> e.mgr.finish(e, t.to(), t.cause());
            default -> {
                // PREPARING
            }
        }
    }

    private void onIntro() {
        e.bossRig.spawn();
        e.stations.spawnInteractions();
        // Boss theme: Core HarborSounds skips keys missing from the pack (no fallback sound)
        HarborSounds.play(
                e.onlinePlayers(),
                e.def.presentation().music(),
                HarborSounds.Options.DEFAULT.withSource(Sound.Source.RECORD));
        e.bossRig.roar();
        e.hud.titleAll("boss.phase.intro.title", "boss.phase.intro.subtitle");
        e.mgr.chunkHold(e, true);
    }
}
