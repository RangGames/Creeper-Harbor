package wiki.creeper.harbor.fishing.api;

import net.kyori.adventure.text.Component;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Settings for a tension minigame opened externally, e.g. by the Boss anchor ({@link
 * FishingService#openTension}, docs 20 3.2 proposed).
 *
 * @param title boss bar name
 * @param grade grade to take the boss bar color and base tension values from (balance.yml
 *     fishing.minigame.<grade>)
 * @param hp opponent health (grade default if 0 or less)
 * @param tensionRiseMult multiplier applied to the grade tension rise (so it can change per boss
 *     phase)
 * @param extraLineStrength value added to the gear line strength (Boss applies anchor_tension_limit
 *     etc.)
 * @param patterns whether to use patterns (if false, Boss shakes it directly with {@link
 *     TensionSession#addTension})
 * @param endOnCaught whether to end as CAUGHT when health reaches 0 (if false, it stops at 0 and
 *     Boss ends it with {@link TensionSession#close})
 */
public record TensionSpec(
        Component title,
        Grade grade,
        double hp,
        double tensionRiseMult,
        double extraLineStrength,
        boolean patterns,
        boolean endOnCaught) {

    public TensionSpec {
        if (tensionRiseMult <= 0) {
            throw new IllegalArgumentException("tensionRiseMult > 0");
        }
    }

    /** Grade defaults as is. */
    public static TensionSpec of(Component title, Grade grade) {
        return new TensionSpec(title, grade, 0, 1, 0, true, true);
    }
}
