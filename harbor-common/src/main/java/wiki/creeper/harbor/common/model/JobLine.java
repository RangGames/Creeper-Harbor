package wiki.creeper.harbor.common.model;

/** Line chosen at the second job advancement. */
public enum JobLine implements Keyed {
    EXPLORE,
    COLLECT,
    PRODUCE,
    ECONOMY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
