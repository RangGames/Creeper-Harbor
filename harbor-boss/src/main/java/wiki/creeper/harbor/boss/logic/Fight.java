package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.boss.api.EncounterState;

/**
 * 보스전 페이즈 상태 기계 (docs 20 10.4 BossPhase). 서버 API를 쓰지 않고 틱 번호만 받아서, 가짜 시계로 JUnit 검사를 한다 (10.6). 런타임
 * {@code Encounter}가 매 틱 {@link #tick}을 부르고, 피해·내구도·줄 끊김·QTE 결과를 알려 준다. 바뀐 상태는 {@link Transition}
 * 목록으로 돌려줘서 호출한 쪽이 이벤트·연출을 낸다.
 *
 * <pre>
 * PREPARING ─(30초 또는 전원 준비)→ INTRO ─(5초)→ HOOK ─(걸기 100%)→ COUNTER ─(체력 ≤ hp_below)→ FINAL_TUG ─(2승)→ CLEARED
 *                                                           ↑                    └(2패, 1회만 체력 +20%)┘
 * 공통: 제한 시간 초과 → FAILED, 전원 이탈 60초 → FAILED, 배 내구도 0 → FAILED, 서버 종료 → ABORTED
 * </pre>
 */
public final class Fight {

    /** 현재 페이즈 (sealed interface + switch 패턴 매칭). */
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
     * @param round 지금 라운드 (1부터)
     */
    public record FinalTug(int round, int successes, int failures) implements Phase {
        @Override
        public EncounterState state() {
            return EncounterState.FINAL_TUG;
        }
    }

    public record Ended(EncounterState state, String cause) implements Phase {}

    /**
     * 규칙 (조우 시작 때 정의·balance에서 한 번 만든다).
     *
     * @param hookTicks 걸기 게이지 0 → 100%에 필요한 안전 구간 틱
     * @param lineBreakCap 줄이 끊기면 게이지를 이 값 이하로
     * @param hookDecayPerTick 앵커가 없을 때 틱당 감소
     * @param finalTugBelow 최후의 줄다리기 진입 체력 비율
     * @param qteWin 라운드 성공 몇 번이면 클리어
     * @param qteLose 라운드 실패 몇 번이면 반격으로 되돌림
     * @param recoverRatio 되돌릴 때 회복 비율 (1회까지)
     * @param timeLimitTicks 제한 시간 (INTRO부터 센다)
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
     * 틱마다 넘기는 입력.
     *
     * @param anchorsInSafe 텐션 안전 구간에 있는 앵커 수
     * @param anchorsActive 줄을 걸고 있는 앵커 수
     * @param anchorGrace 앵커 교대 유예 중 (게이지 고정)
     * @param allAbsent 파티원이 모두 경기장 밖·접속 종료
     */
    public record Inputs(
            int anchorsInSafe, int anchorsActive, boolean anchorGrace, boolean allAbsent) {
        public static final Inputs IDLE = new Inputs(0, 0, false, false);
    }

    /** 상태 전환 한 건. */
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

    // ------------------------------------------------------------------ 시작

    /** 준비 단계부터 시작한다 (파티 확정·역할 선택). */
    public List<Transition> begin(long now) {
        tick = now;
        phase = new Preparing(now + rules.prepareTicks());
        return List.of();
    }

    /** 준비 없이 등장 연출부터 (월드 보스, 인스턴스 도착 완료). */
    public List<Transition> beginIntro(long now) {
        tick = now;
        List<Transition> out = new ArrayList<>();
        startIntro(out, "start");
        return out;
    }

    /** 파티원이 모두 준비를 마쳤다. */
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

    // ------------------------------------------------------------------ 틱

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
                // 체력·내구도 판정은 damage·hullDamage에서 한다
            }
            case FinalTug _ -> {
                // 라운드 결과는 roundResult로 받는다
            }
            case Ended _ -> {
                // 위에서 걸렀다
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

    // ------------------------------------------------------------------ 외부 사건

    /**
     * 피해 (작살·앵커 감기). 반격 단계에서만 들어간다.
     *
     * @return 실제로 줄어든 체력
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

    /** 배 내구도 피해. 걸기·반격 단계에서만. 0이 되면 실패. */
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
     * 수리.
     *
     * @return 실제로 복구한 내구도
     */
    public int repair(int amount) {
        if (amount <= 0 || phase instanceof Ended) {
            return 0;
        }
        int before = hull;
        hull = Math.min(hullMax, hull + amount);
        return hull - before;
    }

    /** 앵커 줄이 끊김 (걸기 게이지를 cap 이하로 되돌림). */
    public void lineBroken() {
        if (phase instanceof Hook) {
            hookGauge = Math.min(hookGauge, rules.lineBreakCap());
        }
    }

    /** 줄다리기 라운드 하나의 결과. */
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
                // 회복은 1회까지. 그 뒤로는 제한 시간 안에서 줄다리기를 처음부터 다시 한다 (추가 제안)
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

    /** 강제 종료 (관리자·서버 종료). 이미 끝났으면 아무것도 하지 않는다. */
    public List<Transition> end(EncounterState result, String cause) {
        List<Transition> out = new ArrayList<>();
        if (!result.ended()) {
            throw new IllegalArgumentException("종료 상태가 아닙니다: " + result);
        }
        end(out, result, cause);
        return out;
    }

    /**
     * 최대 체력을 바꾸되 진행률(체력 비율)은 유지한다 (월드 보스 인원 비례 체력, 10초마다).
     *
     * @return 바뀌었으면 true
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

    /** 저장된 진행률로 되살린다 (월드 보스 resume). */
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

    // ------------------------------------------------------------------ 조회

    public Phase phase() {
        return phase;
    }

    public EncounterState state() {
        return phase.state();
    }

    public long now() {
        return tick;
    }

    /** INTRO가 시작된 틱 (아직이면 −1). */
    public long runStart() {
        return runStart;
    }

    /** 전투 시간 (틱). */
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
