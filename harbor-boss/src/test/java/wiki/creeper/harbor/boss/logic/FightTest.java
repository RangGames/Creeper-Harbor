package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.boss.api.EncounterState;

/** Phase state machine (ticks advanced with a fake clock, docs 20 10.6). */
class FightTest {

    private static final Fight.Rules RULES =
            new Fight.Rules(600, 100, 200, 0.5, 0.05 / 20, 0.2, 2, 2, 0.2, 6000, 1200);

    private static final Fight.Inputs SAFE = new Fight.Inputs(1, 1, false, false);

    private long now;

    private Fight started() {
        Fight f = new Fight(RULES, 1000, 100);
        now = 0;
        f.begin(now);
        return f;
    }

    private List<Fight.Transition> run(Fight f, int ticks, Fight.Inputs in) {
        List<Fight.Transition> all = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            all.addAll(f.tick(++now, in));
        }
        return all;
    }

    @Test
    void prepareTimesOutIntoIntroThenHook() {
        Fight f = started();
        run(f, 599, Fight.Inputs.IDLE);
        assertEquals(EncounterState.PREPARING, f.state());
        run(f, 1, Fight.Inputs.IDLE);
        assertEquals(EncounterState.INTRO, f.state());
        run(f, 100, Fight.Inputs.IDLE);
        assertEquals(EncounterState.HOOK, f.state());
    }

    @Test
    void allReadySkipsWaiting() {
        Fight f = started();
        List<Fight.Transition> t = f.allReady();
        assertEquals(EncounterState.INTRO, f.state());
        assertEquals("ready", t.getFirst().cause());
    }

    private Fight hooked() {
        Fight f = started();
        f.allReady();
        run(f, 100, Fight.Inputs.IDLE);
        assertEquals(EncounterState.HOOK, f.state());
        return f;
    }

    @Test
    void hookGaugeFillsInSafeZoneAndMovesToCounter() {
        Fight f = hooked();
        run(f, 199, SAFE);
        assertEquals(EncounterState.HOOK, f.state());
        List<Fight.Transition> t = run(f, 2, SAFE);
        assertEquals(EncounterState.COUNTER, f.state());
        assertEquals("hooked", t.getFirst().cause());
    }

    @Test
    void lineBreakCapsGaugeAtHalf() {
        Fight f = hooked();
        run(f, 160, SAFE); // 80%
        f.lineBroken();
        assertEquals(0.5, f.hookGauge(), 1e-9);
        // Already below 50%, so unchanged
        Fight g = hooked();
        run(g, 40, SAFE); // 20%
        g.lineBroken();
        assertEquals(0.2, g.hookGauge(), 1e-6);
    }

    @Test
    void hookDecaysWithoutAnchorButNotDuringGrace() {
        Fight f = hooked();
        run(f, 100, SAFE); // 50%
        run(f, 20, new Fight.Inputs(0, 0, true, false));
        assertEquals(0.5, f.hookGauge(), 1e-6);
        run(f, 20, new Fight.Inputs(0, 0, false, false)); // 1s → −5%
        assertEquals(0.45, f.hookGauge(), 1e-6);
    }

    private Fight countering() {
        Fight f = hooked();
        run(f, 200, SAFE);
        assertEquals(EncounterState.COUNTER, f.state());
        return f;
    }

    @Test
    void damageOnlyCountsInCounterAndEntersFinalTug() {
        Fight f = hooked();
        assertEquals(0, f.damage(100, new ArrayList<>()));
        f = countering();
        List<Fight.Transition> out = new ArrayList<>();
        assertEquals(700, f.damage(700, out));
        assertTrue(out.isEmpty());
        f.damage(100, out); // 20% → tug-of-war
        assertEquals(EncounterState.FINAL_TUG, f.state());
        assertInstanceOf(Fight.FinalTug.class, f.phase());
    }

    @Test
    void hullZeroFails() {
        Fight f = countering();
        List<Fight.Transition> out = new ArrayList<>();
        f.hullDamage(60, out);
        assertEquals(30, f.repair(30));
        assertEquals(30, f.repair(50)); // only up to the max
        assertEquals(0, f.repair(30)); // full
        f.hullDamage(200, out);
        assertEquals(EncounterState.FAILED, f.state());
        assertEquals("hull", out.getLast().cause());
    }

    @Test
    void tugTwoWinsClears() {
        Fight f = countering();
        f.damage(800, new ArrayList<>());
        f.roundResult(true);
        f.roundResult(false);
        List<Fight.Transition> t = f.roundResult(true);
        assertEquals(EncounterState.CLEARED, f.state());
        assertEquals("tug", t.getFirst().cause());
    }

    @Test
    void tugTwoLossesRecoversOnceThenRetries() {
        Fight f = countering();
        f.damage(850, new ArrayList<>()); // 15%
        f.roundResult(false);
        f.roundResult(false);
        assertEquals(EncounterState.COUNTER, f.state());
        assertEquals(0.35, f.hpRatio(), 1e-9); // +20%
        assertTrue(f.recovered());
        f.damage(200, new ArrayList<>());
        assertEquals(EncounterState.FINAL_TUG, f.state());
        f.roundResult(false);
        List<Fight.Transition> t = f.roundResult(false);
        assertEquals(EncounterState.FINAL_TUG, f.state());
        assertEquals("tug_retry", t.getFirst().cause());
        assertEquals(new Fight.FinalTug(1, 0, 0), f.phase());
    }

    @Test
    void timeLimitFails() {
        Fight f = countering();
        run(f, 6000, Fight.Inputs.IDLE);
        assertEquals(EncounterState.FAILED, f.state());
    }

    @Test
    void allAbsentForSixtySecondsFails() {
        Fight f = countering();
        Fight.Inputs gone = new Fight.Inputs(0, 0, false, true);
        run(f, 1199, gone);
        assertEquals(EncounterState.COUNTER, f.state());
        run(f, 5, Fight.Inputs.IDLE); // reset on return
        run(f, 1199, gone);
        assertEquals(EncounterState.COUNTER, f.state());
        run(f, 2, gone);
        assertEquals(EncounterState.FAILED, f.state());
    }

    @Test
    void endIsIdempotent() {
        Fight f = countering();
        assertFalse(f.end(EncounterState.ABORTED, "shutdown").isEmpty());
        assertTrue(f.end(EncounterState.CLEARED, "admin").isEmpty());
        assertEquals(EncounterState.ABORTED, f.state());
    }

    @Test
    void rescaleKeepsProgress() {
        Fight f = countering();
        f.damage(400, new ArrayList<>());
        f.rescale(5000);
        assertEquals(0.6, f.hpRatio(), 1e-9);
        assertEquals(3000, f.hp(), 1e-9);
    }
}
