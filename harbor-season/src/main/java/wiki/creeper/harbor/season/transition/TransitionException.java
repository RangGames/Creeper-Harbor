package wiki.creeper.harbor.season.transition;

/** 전환 단계 실패 (관리자에게 보여줄 사유). */
public final class TransitionException extends Exception {

    private static final long serialVersionUID = 1L;

    public TransitionException(String message) {
        super(message);
    }
}
