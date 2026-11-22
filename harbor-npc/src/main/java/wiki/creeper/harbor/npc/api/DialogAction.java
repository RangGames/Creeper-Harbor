package wiki.creeper.harbor.npc.api;

/**
 * Dialog choice action (docs 20 5.2). A choice's actions run top to bottom, and if a result is not
 * {@link ActionResult.Continue} the remaining actions are skipped. Runs on the main thread. Return
 * {@link ActionResult#async} if the DB is needed.
 *
 * <p>There is no action that gives items directly: items are granted only as quest rewards (blocks
 * duplication via repeated dialogs).
 */
@FunctionalInterface
public interface DialogAction {

    ActionResult run(DialogContext ctx, DialogArgs args);

    /** Checks the arguments during the content check phase. Reports problems via check.error. */
    default void validate(DialogArgs args, ArgCheck check) {}
}
