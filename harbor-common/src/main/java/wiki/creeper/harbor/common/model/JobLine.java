package wiki.creeper.harbor.common.model;

/** 2차 전직 때 고르는 계열. */
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
