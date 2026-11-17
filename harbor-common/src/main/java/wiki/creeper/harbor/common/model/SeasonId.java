package wiki.creeper.harbor.common.model;

/** 시즌 번호 (1부터). */
public record SeasonId(int value) {

    public SeasonId {
        if (value < 1) {
            throw new IllegalArgumentException("시즌 번호는 1 이상이어야 합니다: " + value);
        }
    }

    @Override
    public String toString() {
        return "S" + value;
    }
}
