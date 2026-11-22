package wiki.creeper.harbor.npc.api;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** Result of a dialog action. */
public sealed interface ActionResult {

    /**
     * Proceed to the next action. If all are Continue, follow the choice's goto, or close if there
     * is no goto.
     */
    record Continue() implements ActionResult {}

    /** Skip the remaining actions and go to this node. */
    record GoTo(String node) implements ActionResult {}

    /** Go to the page-th (0-based) line of the current node. */
    record Page(int page) implements ActionResult {}

    /** Close the dialog. */
    record Close() implements ActionResult {}

    /**
     * The action opened another screen (GUI, confirmation Dialog, cutscene). Only end the dialog
     * session and leave the screen alone.
     */
    record Handoff() implements ActionResult {}

    /**
     * Failure (e.g. quest:accept conditions not met). If messageKey is set it is sent to chat; if
     * node is set go to that guidance node, otherwise show the current node again.
     */
    record Fail(@Nullable String node, @Nullable String messageKey) implements ActionResult {}

    /**
     * Asynchronous result. Clicks in this session are ignored until it finishes. The result is
     * handled on the main thread.
     */
    record Async(CompletableFuture<ActionResult> result) implements ActionResult {}

    ActionResult CONTINUE = new Continue();
    ActionResult CLOSE = new Close();
    ActionResult HANDOFF = new Handoff();

    static ActionResult goTo(String node) {
        return new GoTo(node);
    }

    static ActionResult fail(@Nullable String node) {
        return new Fail(node, null);
    }

    static ActionResult failMessage(String messageKey) {
        return new Fail(null, messageKey);
    }

    static ActionResult async(CompletableFuture<ActionResult> result) {
        return new Async(result);
    }
}
