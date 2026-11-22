package wiki.creeper.harbor.fishing.session;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.content.FishingBalance.MinigameSpec;

/**
 * Fight minigame calculation (docs 20 3.4, 20Hz). Uses no server API (unit tests).
 *
 * <ul>
 *   <li>Tension T (0..1, 1 = limit) and fish health H
 *   <li>Reeling: T rises. Inside the safe zone, H −= reel × direction factor (match 1.0, mismatch
 *       0.4)
 *   <li>Letting out: T falls. If T = 0 for escape_ticks, the fish escapes
 *   <li>T ≥ 1 snaps the line
 *   <li>Patterns: rush (tension spike), dive (reel efficiency −50%), jump (big damage if released
 *       after the warning), rest (reel efficiency ×1.5)
 *   <li>Legendaries change phase and speed up when H reaches 66% and 33%
 * </ul>
 */
public final class Minigame {

    /** Player input (per tick). left/right follow the fish direction. */
    public record Input(boolean reeling, boolean left, boolean right) {}

    /** Direction the fish pulls. */
    public enum Direction {
        LEFT,
        CENTER,
        RIGHT
    }

    /** Pattern. */
    public enum Pattern {
        DASH,
        DIVE,
        JUMP,
        REST
    }

    /**
     * Gear and stat modifiers.
     *
     * @param lineStrength line strength (+8% tension limit per point)
     * @param reelSpeed reel_speed stat (default 1.0)
     * @param safeZone tension_safe_zone stat (default 1.0)
     * @param stability rod stability (0..1, reduces shaking)
     * @param warnTicks pattern warning ticks (base + pattern_warn + ping compensation)
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

    /** Advances one tick. Returns the result when finished. */
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

    /**
     * Whether the current input follows the fish direction (input that does not reduce reel
     * efficiency). For UI display.
     */
    public boolean followsDirection(Input input) {
        return directionFactor(input) >= 1.0;
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
                    // Jump: big damage if letting out when the warning ends, tension spike if
                    // reeling
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

    // ------------------------------------------------------------------ State (UI)

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

    /** External force (boss pattern): adds tension. */
    public void addTension(double delta) {
        if (result == null) {
            tension = Math.clamp(tension + delta, 0, 1.2);
        }
    }

    /** External damage (harpoon): fraction of max health. */
    public void damage(double fraction) {
        if (result == null) {
            hp = Math.max(0, hp - spec.hp() * fraction);
            updatePhase();
        }
    }

    /** If false, it does not end even when health reaches 0 (Boss ends it directly). */
    public void setEndOnCaught(boolean endOnCaught) {
        this.endOnCaught = endOnCaught;
    }

    /** External abort (death, leaving, disconnect). */
    public void abort(MinigameResult reason) {
        if (result == null) {
            result = reason;
        }
    }
}
