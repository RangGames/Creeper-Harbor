package wiki.creeper.harbor.season.transition;

/** Transition step failure (reason shown to admins). */
public final class TransitionException extends Exception {

    private static final long serialVersionUID = 1L;

    public TransitionException(String message) {
        super(message);
    }
}
