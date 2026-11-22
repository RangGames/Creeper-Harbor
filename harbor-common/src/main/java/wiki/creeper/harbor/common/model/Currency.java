package wiki.creeper.harbor.common.model;

/** Currency. Pearls (PEARL) are a paid currency, managed separately by the Shop module. */
public enum Currency implements Keyed {
    GOLD,
    MULBINEUL,
    PEARL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** Whether this is an in-game currency (gold, mulbineul). */
    public boolean isGameCurrency() {
        return this != PEARL;
    }
}
