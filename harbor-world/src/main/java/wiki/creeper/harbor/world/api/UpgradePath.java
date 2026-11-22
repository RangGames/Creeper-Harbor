package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** Ship upgrade path (speed · cargo · hull × 3 steps, docs 20 8.4). */
public enum UpgradePath implements Keyed {
    SPEED,
    CARGO,
    HULL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
