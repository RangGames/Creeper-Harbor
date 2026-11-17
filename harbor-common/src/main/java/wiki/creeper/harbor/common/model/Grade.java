package wiki.creeper.harbor.common.model;

/** 등급. 어종·아이템 공통. 선언 순서가 낮은 등급 → 높은 등급. */
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
