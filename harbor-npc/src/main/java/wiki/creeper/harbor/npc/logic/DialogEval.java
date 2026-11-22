package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;

/**
 * Dialog tree evaluation (no server API). The caller decides whether a single condition is true.
 */
public final class DialogEval {

    private DialogEval() {}

    /** Whether all conditions in the list are true (true if empty). */
    public static boolean all(List<DialogRef> conditions, Predicate<DialogRef> test) {
        for (DialogRef c : conditions) {
            if (!test.test(c)) {
                return false;
            }
        }
        return true;
    }

    /** Evaluates entries top to bottom and returns the first matching node. */
    public static Optional<String> entry(DialogDef dialog, Predicate<DialogRef> test) {
        for (DialogDef.Entry entry : dialog.entries()) {
            if (all(entry.conditions(), test)) {
                return Optional.of(entry.node());
            }
        }
        return Optional.empty();
    }

    /** A choice whose conditions match, with its original index. */
    public record Visible(int index, DialogDef.Option option) {}

    public static List<Visible> visibleOptions(DialogDef.Node node, Predicate<DialogRef> test) {
        List<Visible> visible = new ArrayList<>();
        List<DialogDef.Option> options = node.options();
        for (int i = 0; i < options.size(); i++) {
            DialogDef.Option o = options.get(i);
            if (all(o.conditions(), test)) {
                visible.add(new Visible(i, o));
            }
        }
        return visible;
    }

    /**
     * Whether to show choices on this page (only the last page, or immediately if there are no
     * pages).
     */
    public static boolean optionsOnPage(DialogDef.Node node, int page) {
        return optionsOnPage(node.pages().size(), page);
    }

    /** Clamps the page number into range. */
    public static int clampPage(DialogDef.Node node, int page) {
        return clampPage(node.pages().size(), page);
    }

    /** By page count alone (Speech lines opened by other modules follow the same rule). */
    public static boolean optionsOnPage(int pageCount, int page) {
        return page >= pageCount - 1;
    }

    public static int clampPage(int pageCount, int page) {
        return Math.clamp(page, 0, Math.max(0, pageCount - 1));
    }
}
