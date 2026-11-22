package wiki.creeper.harbor.season.transition;

import java.util.function.Consumer;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.season.api.SeasonHookContext;

/** SeasonHookContext implementation. */
record HookContext(
        String transitionId,
        int fromSeason,
        int toSeason,
        boolean dryRun,
        Database database,
        Consumer<String> reporter)
        implements SeasonHookContext {

    @Override
    public void report(String message) {
        reporter.accept(message);
    }
}
