package wiki.creeper.harbor.common.model;

/** Grade. Shared by fish and items. Declaration order is lowest → highest grade. */
public enum Grade implements Keyed {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC,
    LEGENDARY,
    MYTHIC;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    public boolean isAtLeast(Grade other) {
        return compareTo(other) >= 0;
    }
}
