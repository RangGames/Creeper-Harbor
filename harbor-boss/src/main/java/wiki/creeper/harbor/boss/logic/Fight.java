package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.boss.api.EncounterState;

/**
 * Boss fight phase state machine (docs 20 10.4 BossPhase). Uses no server API and takes only tick
 * numbers, so JUnit tests run with a fake clock (10.6). The runtime {@code Encounter} calls {@link
 * #tick} every tick and reports damage, durability, line snaps and QTE results. Changed states are
 * returned as a list of {@link Transition}s so the caller can fire events and effects.
 *
 * <pre>
 * PREPARING ─(30s or all ready)→ INTRO ─(5s)→ HOOK ─(hook 100%)→ COUNTER ─(HP ≤ hp_below)→ FINAL_TUG ─(2 wins)→ CLEARED
 *                                                                  ↑                    └(2 losses, HP +20% once)┘
 * Always: time limit exceeded → FAILED, everyone absent 60s → FAILED, ship durability 0 → FAILED, server shutdown → ABORTED
 * </pre>
 */
public final class Fight {

    /** Current phase (sealed interface + switch pattern matching). */
    public sealed interface Phase permits Preparing, Intro, Hook, Counter, FinalTug, Ended {
        EncounterState state();
    }

    public record Preparing(long until) implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.PREPARING;
        }
    }

    public record Intro(long until) implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.INTRO;
        }
    }

    public record Hook() implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.HOOK;
        }
    }

    public record Counter() implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.COUNTER;
        }
    }

    /**
     * @param round current round (starting at 1)
     */
    public record FinalTug(int round, int successes, int failures) implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.FINAL_TUG;
        }
    }

    public record Ended(EncounterState state, String cause) implements Phase {}

    /**
     * Rules (built once at encounter start from the definition and balance).
     *
     * @param hookTicks safe-zone ticks needed to take the hook gauge from 0 to 100%
     * @param lineBreakCap when a line snaps, the gauge is reset to at most this value
     * @param hookDecayPerTick decay per tick while there is no anchor
     * @param finalTugBelow HP ratio for entering the final tug-of-war
     * @param qteWin number of round wins needed to clear
     * @param qteLose number of round losses that send it back to counter
     * @param recoverRatio HP ratio restored when sent back (at most once)
     * @param timeLimitTicks time limit (counted from INTRO)
     */
    public record Rules(
            int prepareTicks,
            int introTicks,
            int hookTicks,
            double lineBreakCap,
            double hookDecayPerTick,
            double finalTugBelow,
            int qteWin,
            int qteLose,
            double recoverRatio,
            long timeLimitTicks,
            int allAbsentFailTicks) {}

    /**
     * Input passed every tick.
     *
     * @param anchorsInSafe number of anchors in the tension safe zone
     * @param anchorsActive number of anchors holding a line
     * @param anchorGrace anchor swap grace in progress (gauge frozen)
     * @param allAbsent all party members are outside the arena or disconnected
     */
    public record Inputs(
            int anchorsInSafe, int anchorsActive, boolean anchorGrace, boolean allAbsent) {
        public static final Inputs IDLE = new Inputs(0, 0, false, false);
    }

    /** One state transition. */
    public record Transition(EncounterState from, EncounterState to, String cause) {}

    private final Rules rules;
    private Phase phase;
    private long tick;
    private long runStart = -1;
    private long absentSince = -1;
    private double maxHp;
    private double hp;
    private final int hullMax;
    private int hull;
    private double hookGauge;
    private boolean recovered;

    public Fight(Rules rules, double maxHp, int hullMax) {
        if (maxHp <= 0 || hullMax <= 0) {
            throw new IllegalArgumentException("maxHp, hullMax > 0");
        }
        this.rules = rules;
        this.maxHp = maxHp;
        this.hp = maxHp;
        this.hullMax = hullMax;
        this.hull = hullMax;
        this.phase = new Preparing(Long.MAX_VALUE);
    }

    // ------------------------------------------------------------------ Start

    /** Starts from the preparation stage (party confirmation, role selection). */
    public List<Transition> begin(long now) {
        tick = now;
        phase = new Preparing(now + rules.prepareTicks());
        return List.of();
    }

    /** Starts from the intro without preparation (world boss, instance arrival complete). */
    public List<Transition> beginIntro(long now) {
        tick = now;
        List<Transition> out = new ArrayList<>();
        startIntro(out, "start");
        return out;
    }

    /** All party members are ready. */
    public List<Transition> allReady() {
        List<Transition> out = new ArrayList<>();
        if (phase instanceof Preparing) {
            startIntro(out, "ready");
        }
        return out;
    }

    private void startIntro(List<Transition> out, String cause) {
        runStart = tick;
        move(out, new Intro(tick + rules.introTicks()), cause);
    }

    // ------------------------------------------------------------------ Tick

    public List<Transition> tick(long now, Inputs in) {
        tick = now;
        List<Transition> out = new ArrayList<>();
        if (phase instanceof Ended) {
            return out;
        }
        if (phase.state().fighting()) {
            if (now - runStart >= rules.timeLimitTicks()) {
                end(out, EncounterState.FAILED, "time");
                return out;
            }
            if (in.allAbsent()) {
                if (absentSince < 0) {
                    absentSince = now;
                } else if (now - absentSince >= rules.allAbsentFailTicks()) {
                    end(out, EncounterState.FAILED, "absent");
                    return out;
                }
            } else {
                absentSince = -1;
            }
        }
        switch (phase) {
            case Preparing p -> {
                if (now >= p.until()) {
                    startIntro(out, "timeout");
                }
            }
            case Intro i -> {
                if (now >= i.until()) {
                    move(out, new Hook(), "intro_done");
                }
            }
            case Hook _ -> tickHook(in, out);
            case Counter _ -> {
                // HP and durability are checked in damage and hullDamage
            }
            case FinalTug _ -> {
                // Round results arrive via roundResult
            }
            case Ended _ -> {
                // Filtered out above
            }
        }
        return out;
    }

    private void tickHook(Inputs in, List<Transition> out) {
        if (in.anchorsInSafe() > 0) {
            hookGauge += 1.0 / Math.max(1, rules.hookTicks());
        } else if (in.anchorsActive() == 0 && !in.anchorGrace()) {
            hookGauge -= rules.hookDecayPerTick();
        }
        hookGauge = Math.clamp(hookGauge, 0.0, 1.0);
        if (hookGauge >= 1.0) {
            move(out, new Counter(), "hooked");
        }
    }

    // ------------------------------------------------------------------ External events

    /**
     * Damage (harpoon, anchor reeling). Only applied in the counter stage.
     *
     * @return HP actually removed
     */
    public double damage(double amount, List<Transition> out) {
        if (!(phase instanceof Counter) || amount <= 0) {
            return 0;
        }
        double before = hp;
        hp = Math.max(0, hp - amount);
        if (hp / maxHp <= rules.finalTugBelow()) {
            move(out, new FinalTug(1, 0, 0), "hp");
        }
        return before - hp;
    }

    /** Ship durability damage. Only in the hook and counter stages. Fails at 0. */
    public int hullDamage(int amount, List<Transition> out) {
        if (amount <= 0 || !(phase instanceof Hook || phase instanceof Counter)) {
            return 0;
        }
        int before = hull;
        hull = Math.max(0, hull - amount);
        if (hull == 0) {
            end(out, EncounterState.FAILED, "hull");
        }
        return before - hull;
    }

    /**
     * Repair.
     *
     * @return durability actually restored
     */
    public int repair(int amount) {
        if (amount <= 0 || phase instanceof Ended) {
            return 0;
        }
        int before = hull;
        hull = Math.min(hullMax, hull + amount);
        return hull - before;
    }

    /** An anchor line snapped (resets the hook gauge to at most cap). */
    public void lineBroken() {
        if (phase instanceof Hook) {
            hookGauge = Math.min(hookGauge, rules.lineBreakCap());
        }
    }

    /** Result of one tug-of-war round. */
    public List<Transition> roundResult(boolean success) {
        List<Transition> out = new ArrayList<>();
        if (!(phase instanceof FinalTug(int round, int successes, int failures))) {
            return out;
        }
        int s = successes + (success ? 1 : 0);
        int f = failures + (success ? 0 : 1);
        if (s >= rules.qteWin()) {
            end(out, EncounterState.CLEARED, "tug");
        } else if (f >= rules.qteLose()) {
            if (!recovered) {
                recovered = true;
                hp = Math.min(maxHp, hp + maxHp * rules.recoverRatio());
                move(out, new Counter(), "tug_failed");
            } else {
                // Recovery happens at most once. After that the tug-of-war restarts from the
                // beginning within the time limit (proposed)
                phase = new FinalTug(1, 0, 0);
                out.add(
                        new Transition(
                                EncounterState.FINAL_TUG, EncounterState.FINAL_TUG, "tug_retry"));
            }
        } else {
            phase = new FinalTug(round + 1, s, f);
        }
        return out;
    }

    /** Forced end (admin, server shutdown). Does nothing if already ended. */
    public List<Transition> end(EncounterState result, String cause) {
        List<Transition> out = new ArrayList<>();
        if (!result.ended()) {
            throw new IllegalArgumentException("종료 상태가 아닙니다: " + result);
        }
        end(out, result, cause);
        return out;
    }

    /**
     * Changes the max HP while keeping progress (HP ratio) (world boss HP scaled by players, every
     * 10 seconds).
     *
     * @return true if it changed
     */
    public boolean rescale(double newMax) {
        if (newMax <= 0 || Math.abs(newMax - maxHp) < 1e-9) {
            return false;
        }
        double ratio = hp / maxHp;
        maxHp = newMax;
        hp = newMax * ratio;
        return true;
    }

    /** Restores from saved progress (world boss resume). */
    public void restore(double hpRatio, double gauge, boolean counter) {
        hp = maxHp * Math.clamp(hpRatio, 0.0, 1.0);
        hookGauge = Math.clamp(gauge, 0.0, 1.0);
        if (counter && phase instanceof Hook) {
            phase = new Counter();
        }
    }

    private void end(List<Transition> out, EncounterState result, String cause) {
        if (phase instanceof Ended) {
            return;
        }
        move(out, new Ended(result, cause), cause);
    }

    private void move(List<Transition> out, Phase next, String cause) {
        EncounterState from = phase.state();
        phase = next;
        out.add(new Transition(from, next.state(), cause));
    }

    // ------------------------------------------------------------------ Lookup

    public Phase phase() {
        return phase;
    }

    public EncounterState state() {
        return phase.state();
    }

    public long now() {
        return tick;
    }

    /** Tick when INTRO started (−1 if not yet). */
    public long runStart() {
        return runStart;
    }

    /** Fight duration (ticks). */
    public long elapsed() {
        return runStart < 0 ? 0 : tick - runStart;
    }

    public long remainingTicks() {
        return runStart < 0
                ? rules.timeLimitTicks()
                : Math.max(0, rules.timeLimitTicks() - elapsed());
    }

    public double hp() {
        return hp;
    }

    public double maxHp() {
        return maxHp;
    }

    public double hpRatio() {
        return hp / maxHp;
    }

    public int hull() {
        return hull;
    }

    public int hullMax() {
        return hullMax;
    }

    public double hookGauge() {
        return hookGauge;
    }

    public boolean recovered() {
        return recovered;
    }

    public Rules rules() {
        return rules;
    }
}
