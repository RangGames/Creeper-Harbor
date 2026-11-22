package wiki.creeper.harbor.common.content;

import wiki.creeper.harbor.common.util.SourcePos;

/**
 * Extra check run after parsing and reference checks. Checks that compare against other entries
 * (duplicate collection numbers, etc.) or that need the server API (vanilla item ID validation,
 * etc.) go here.
 */
@FunctionalInterface
public interface ContentCheck<T> {

    void check(T value, Context ctx);

    /** Information available during the check. */
    interface Context {

        /** The snapshot about to be created (not applied yet). */
        ContentSnapshot snapshot();

        /** Location of the entry being checked. */
        SourcePos pos();

        /** Records an error at the location of the entry being checked. */
        void error(String message);
    }
}
