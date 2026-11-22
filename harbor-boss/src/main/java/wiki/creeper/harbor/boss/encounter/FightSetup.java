package wiki.creeper.harbor.boss.encounter;

import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.HpScaling;

/**
 * Builds the {@link Fight} for a new encounter from the definition snapshot and balance: max HP
 * (party or world scaling), time limit and phase rules.
 */
final class FightSetup {

    private FightSetup() {}

    static Fight create(BossDef def, BossBalance bb, int partySize) {
        double maxHp =
                def.isWorld()
                        ? HpScaling.worldMaxHp(def.hp().base(), 0, bb.worldMinParticipants())
                        : HpScaling.partyMaxHp(def.hp().base(), perPlayerRate(def, bb), partySize);
        int timeLimit =
                def.timeLimitSeconds() > 0
                        ? def.timeLimitSeconds()
                        : bb.timeLimitSeconds(def.type());
        BossDef.PhaseDef hook = def.phase(BossDef.PhaseKind.HOOK).orElseThrow();
        Fight.Rules rules =
                new Fight.Rules(
                        Integer.MAX_VALUE,
                        bb.introTicks(),
                        hook.hookTicks() > 0 ? hook.hookTicks() : bb.hookTicks(),
                        bb.hookLineBreakCap(),
                        bb.hookDecayPerSecond() / 20.0,
                        def.finalTugBelow(),
                        bb.qteWin(),
                        bb.qteLose(),
                        bb.qteRecover(),
                        timeLimit * 20L,
                        bb.allAbsentFailTicks());
        return new Fight(rules, maxHp, def.hull().max());
    }

    private static double perPlayerRate(BossDef def, BossBalance bb) {
        double rate = def.hp().perPlayerRate();
        return rate >= 0 ? rate : bb.perPlayerRate(def.type());
    }
}
