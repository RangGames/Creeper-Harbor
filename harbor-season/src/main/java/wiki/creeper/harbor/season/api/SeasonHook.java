package wiki.creeper.harbor.season.api;

/**
 * Season transition hook (docs 20 11.2). Modules implement cleanup that needs code and register it
 * with {@link SeasonHookRegistry}. Example: Shop auto-grants unclaimed season pass rewards and
 * closes the pass in onArchive.
 *
 * <p>If the transition is interrupted, resume re-runs the same step, so <b>repeated calls must give
 * the same result</b> (unique reference IDs, INSERT IGNORE etc.). Throwing an exception stops the
 * transition at that step; an admin fixes the problem and resumes.
 *
 * <ul>
 *   <li>onArchive: end of the ARCHIVE step (after copying to archive_s<n>, before deleting the
 *       source)
 *   <li>onReset: end of the RESET step (after reset_plan.yml deletes and ledger cleanup)
 *   <li>onOpen: OPEN step (right after the new season becomes ACTIVE, before server restart)
 * </ul>
 */
public interface SeasonHook {

    /** Name for logging (e.g. shop.pass). */
    String id();

    default void onArchive(SeasonHookContext ctx) throws Exception {}

    default void onReset(SeasonHookContext ctx) throws Exception {}

    default void onOpen(SeasonHookContext ctx) throws Exception {}
}
