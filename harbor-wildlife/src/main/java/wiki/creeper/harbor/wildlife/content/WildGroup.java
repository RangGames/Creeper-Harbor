package wiki.creeper.harbor.wildlife.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Temperament ('17-3' 9.3, prototype GROUPS). Determined by the species' special action and can be
 * overridden in species.yml.
 */
public enum WildGroup implements Keyed {
    /** Dash, flee and leap types. */
    ACTIVE,
    NORMAL,
    /** Ambush, basking and staring types. */
    LAZY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
