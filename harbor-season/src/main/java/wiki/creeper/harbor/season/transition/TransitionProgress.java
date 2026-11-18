package wiki.creeper.harbor.season.transition;

import java.util.List;
import java.util.Optional;

/** 일지에서 다음에 할 단계를 정한다 (순수 계산, 재개 판단). */
public final class TransitionProgress {

    private TransitionProgress() {}

    /**
     * 마지막으로 끝난 큰 단계 다음. 아무것도 끝나지 않았으면 PRECHECK, OPEN까지 끝났으면 빈 Optional.
     *
     * <p>큰 단계 사이에 빈 곳이 있으면 (일지가 손상됐으면) 처음 빠진 단계부터 한다.
     */
    public static Optional<TransitionStep> next(List<LogRow> rows) {
        for (TransitionStep step : TransitionStep.values()) {
            if (!isDone(rows, step.name())) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    public static boolean isDone(List<LogRow> rows, String step) {
        return rows.stream().anyMatch(r -> r.step().equals(step) && r.done());
    }

    /** 이 단계가 시작된 적이 있는지 (상태 무관). */
    public static boolean started(List<LogRow> rows, String step) {
        return rows.stream()
                .anyMatch(r -> r.step().equals(step) || r.step().startsWith(step + ":"));
    }

    /** RESET 이후 단계가 시작됐는지 (롤백 A 불가). */
    public static boolean pastRollbackA(List<LogRow> rows) {
        for (TransitionStep step : TransitionStep.values()) {
            if (step.pastRollbackA() && started(rows, step.name())) {
                return true;
            }
        }
        return false;
    }
}
