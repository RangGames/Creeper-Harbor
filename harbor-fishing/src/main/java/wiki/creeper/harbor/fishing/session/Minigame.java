package wiki.creeper.harbor.fishing.session;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.content.FishingBalance.MinigameSpec;

/**
 * 파이팅 미니게임 계산 (docs 20 3.4, 20Hz). 서버 API를 쓰지 않는다 (단위 테스트).
 *
 * <ul>
 *   <li>텐션 T(0~1, 1 = 한계)와 물고기 체력 H
 *   <li>감는 중: T 상승. 안전 구간 안이면 H −= reel × 방향 계수(일치 1.0, 불일치 0.4)
 *   <li>푸는 중: T 하강. T = 0으로 escape_ticks 지나면 도망
 *   <li>T ≥ 1이면 줄 끊김
 *   <li>패턴: 돌진(텐션 급상승), 잠수(감기 효율 −50%), 점프(예고 후 놓으면 큰 피해), 휴식(감기 효율 ×1.5)
 *   <li>전설은 H가 66%·33%에 닿으면 페이즈가 바뀌고 빨라진다
 * </ul>
 */
public final class Minigame {

    /** 플레이어 입력 (틱마다). left·right는 물고기 방향 따라가기. */
    public record Input(boolean reeling, boolean left, boolean right) {}

    /** 물고기가 끄는 방향. */
    public enum Direction {
        LEFT,
        CENTER,
        RIGHT
    }

    /** 패턴. */
    public enum Pattern {
        DASH,
        DIVE,
        JUMP,
        REST
    }

    /**
     * 장비·스탯 보정.
     *
     * @param lineStrength 줄 강도 (1당 텐션 한계 +8%)
     * @param reelSpeed reel_speed 스탯 (기본 1.0)
     * @param safeZone tension_safe_zone 스탯 (기본 1.0)
     * @param stability 낚싯대 안정성 (0~1, 흔들림 감소)
     * @param warnTicks 패턴 예고 틱 (기본 + pattern_warn + 핑 보정)
     */
    public record Gear(
            double lineStrength,
            double reelSpeed,
            double safeZone,
            double stability,
            int warnTicks) {}

    private static final int PATTERN_TICKS = 30;
    private static final int JUMP_WINDOW = 10;
    private static final double JUMP_DAMAGE = 0.08;
    private static final double JUMP_SPIKE = 0.25;
    private static final double NOISE = 0.004;

    private final MinigameSpec spec;
    private final Gear gear;
    private final RandomGenerator random;
    private final double lineStep;
    private final List<Pattern> pool = new ArrayList<>();
    private double tension = 0.3;
    private double hp;
    private int ticks;
    private int zeroTicks;
    private Direction direction = Direction.CENTER;
    private int directionTicks;
    private int phase = 1;
    private int nextPatternTick;
    private @Nullable Pattern warning;
    private int warningLeft;
    private @Nullable Pattern active;
    private int activeLeft;
    private @Nullable MinigameResult result;
    private boolean endOnCaught = true;

    public Minigame(MinigameSpec spec, Gear gear, double lineStrengthStep, RandomGenerator random) {
        this.spec = spec;
        this.gear = gear;
        this.random = random;
        this.lineStep = lineStrengthStep;
        this.hp = spec.hp();
        List<Pattern> all = new ArrayList<>(List.of(Pattern.values()));
        for (int i = all.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Pattern tmp = all.get(i);
            all.set(i, all.get(j));
            all.set(j, tmp);
        }
        pool.addAll(all.subList(0, Math.min(spec.patterns(), all.size())));
        this.nextPatternTick = intervalTicks();
    }

    /** 한 틱 진행. 끝나면 결과를 돌려준다. */
    public @Nullable MinigameResult tick(Input input) {
        if (result != null) {
            return result;
        }
        ticks++;
        updateDirection();
        updatePatterns(input);

        double limitFactor = 1 + lineStep * gear.lineStrength();
        double rise = spec.tensionRise() * phaseMult() / limitFactor;
        double efficiency = 1.0;
        if (active == Pattern.DASH) {
            rise *= 2.5;
            tension += 0.01 / limitFactor;
        } else if (active == Pattern.DIVE) {
            efficiency = 0.5;
        } else if (active == Pattern.REST) {
            efficiency = 1.5;
        }

        if (input.reeling()) {
            tension += rise;
            if (inSafeZone()) {
                hp -= spec.reelPerTick() * gear.reelSpeed() * directionFactor(input) * efficiency;
            }
        } else {
            tension -= spec.releaseRate();
        }
        tension += (random.nextDouble() * 2 - 1) * NOISE * (1 - Math.clamp(gear.stability(), 0, 1));
        tension = Math.clamp(tension, 0, 1.2);

        updatePhase();
        if (hp <= 0 && !endOnCaught) {
            hp = 0;
        }
        if (hp <= 0 && endOnCaught) {
            result = MinigameResult.CAUGHT;
        } else if (tension >= 1) {
            result = MinigameResult.LINE_BROKE;
        } else {
            zeroTicks = tension <= 0 ? zeroTicks + 1 : 0;
            if (zeroTicks >= spec.escapeTicks()) {
                result = MinigameResult.ESCAPED;
            }
        }
        return result;
    }

    private void updateDirection() {
        if (--directionTicks > 0) {
            return;
        }
        Direction[] values = Direction.values();
        direction = values[random.nextInt(values.length)];
        directionTicks = 30 + random.nextInt(31);
    }

    private double directionFactor(Input input) {
        return switch (direction) {
            case LEFT -> input.left() && !input.right() ? 1.0 : 0.4;
            case RIGHT -> input.right() && !input.left() ? 1.0 : 0.4;
            case CENTER -> input.left() || input.right() ? 0.7 : 1.0;
        };
    }

    private void updatePatterns(Input input) {
        if (activeLeft > 0 && --activeLeft == 0) {
            active = null;
        }
        if (warning != null) {
            if (--warningLeft <= 0) {
                Pattern started = warning;
                warning = null;
                if (started == Pattern.JUMP) {
                    // 점프: 예고가 끝나는 순간 풀고 있으면 큰 피해, 감고 있으면 텐션 급상승
                    if (input.reeling()) {
                        tension += JUMP_SPIKE;
                    } else {
                        hp -= spec.hp() * JUMP_DAMAGE;
                    }
                    active = Pattern.JUMP;
                    activeLeft = JUMP_WINDOW;
                } else {
                    active = started;
                    activeLeft = PATTERN_TICKS;
                }
            }
            return;
        }
        if (pool.isEmpty() || ticks < nextPatternTick || active != null) {
            return;
        }
        warning = pool.get(random.nextInt(pool.size()));
        warningLeft = Math.max(1, gear.warnTicks());
        nextPatternTick = ticks + intervalTicks();
    }

    private int intervalTicks() {
        double seconds =
                spec.patternIntervalSeconds() * (phase == 1 ? 1 : Math.pow(0.8, phase - 1));
        return (int) Math.max(20, Math.round(seconds * 20));
    }

    private void updatePhase() {
        if (!spec.phased()) {
            return;
        }
        double ratio = hp / spec.hp();
        int next = ratio <= 1.0 / 3 ? 3 : ratio <= 2.0 / 3 ? 2 : 1;
        phase = Math.max(phase, next);
    }

    private double phaseMult() {
        return phase == 1 ? 1 : Math.pow(1.15, phase - 1);
    }

    // ------------------------------------------------------------------ 상태 (UI)

    public double tension() {
        return Math.min(tension, 1);
    }

    public double safeLow() {
        return spec.safeLow();
    }

    public double safeHigh() {
        return Math.min(0.95, spec.safeLow() + spec.safeWidth() * gear.safeZone());
    }

    public boolean inSafeZone() {
        return tension >= safeLow() && tension <= safeHigh();
    }

    public double hpRatio() {
        return Math.max(0, hp / spec.hp());
    }

    public int ticks() {
        return ticks;
    }

    public int phase() {
        return phase;
    }

    public Direction direction() {
        return direction;
    }

    public @Nullable Pattern warning() {
        return warning;
    }

    public @Nullable Pattern active() {
        return active;
    }

    public @Nullable MinigameResult result() {
        return result;
    }

    /** 외부 힘 (보스 패턴): 텐션을 더한다. */
    public void addTension(double delta) {
        if (result == null) {
            tension = Math.clamp(tension + delta, 0, 1.2);
        }
    }

    /** 외부 피해 (작살): 최대 체력 대비 비율. */
    public void damage(double fraction) {
        if (result == null) {
            hp = Math.max(0, hp - spec.hp() * fraction);
            updatePhase();
        }
    }

    /** false면 체력이 0이 되어도 끝내지 않는다 (Boss가 직접 끝냄). */
    public void setEndOnCaught(boolean endOnCaught) {
        this.endOnCaught = endOnCaught;
    }

    /** 외부 중단 (사망·이탈·접속 종료). */
    public void abort(MinigameResult reason) {
        if (result == null) {
            result = reason;
        }
    }
}
