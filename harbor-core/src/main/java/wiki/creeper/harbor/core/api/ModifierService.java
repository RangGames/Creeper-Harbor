package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Modifier pipeline. Results are cached per player and recomputed only when modifiers change or
 * expire. Used on the main thread.
 *
 * <p>Per-stat base, max and min values are read from {@code stats.<key>.base / min / max} in
 * balance.yml.
 */
public interface ModifierService {

    void add(UUID player, Modifier modifier);

    /** Removes all modifiers with the same source. */
    void removeSource(UUID player, String source);

    /** Modifier applied to every player (sea events, etc.). */
    void addGlobal(Modifier modifier);

    void removeGlobalSource(String source);

    /** Computes from the balance.yml base value. */
    double value(UUID player, StatKey stat);

    /** For when the base value varies by situation (per-species purchase price, etc.). */
    double apply(UUID player, StatKey stat, double base);

    /** Contribution per source (/harbor stat). */
    Breakdown explain(UUID player, StatKey stat, double base);

    /** Calculation breakdown. */
    record Breakdown(double base, double result, List<Modifier> applied, double min, double max) {}
}
