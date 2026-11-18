package wiki.creeper.harbor.season.transition;

import java.util.Optional;

/** 시즌 전환 10단계 (docs 20 11.4). 순서대로만 진행하고, RESET만 원본을 지운다. */
public enum TransitionStep {
    PRECHECK(1),
    NOTICE(2),
    FREEZE(3),
    VERIFY(4),
    BACKUP(5),
    ARCHIVE(6),
    SNAPSHOT(7),
    RESET(8),
    CONTENT(9),
    OPEN(10);

    private final int number;

    TransitionStep(int number) {
        this.number = number;
    }

    /** 문서의 단계 번호 (1~10). */
    public int number() {
        return number;
    }

    public Optional<TransitionStep> next() {
        for (TransitionStep step : values()) {
            if (step.number == number + 1) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /** 이 단계가 시작됐으면 롤백 A(아카이브 스키마 삭제)로는 되돌릴 수 없다. */
    public boolean pastRollbackA() {
        return number >= RESET.number;
    }

    public static Optional<TransitionStep> parse(String raw) {
        for (TransitionStep step : values()) {
            if (step.name().equals(raw)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }
}
