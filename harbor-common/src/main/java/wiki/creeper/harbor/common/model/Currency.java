package wiki.creeper.harbor.common.model;

/** 재화. 진주(PEARL)는 유료 재화라 Shop 모듈이 따로 관리한다. */
public enum Currency implements Keyed {
    GOLD,
    MULBINEUL,
    PEARL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** 게임 재화(골드·물비늘)인지. */
    public boolean isGameCurrency() {
        return this != PEARL;
    }
}
