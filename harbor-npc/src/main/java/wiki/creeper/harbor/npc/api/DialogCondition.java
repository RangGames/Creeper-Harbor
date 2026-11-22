package wiki.creeper.harbor.npc.api;

/**
 * Dialog condition (docs 20 5.4). Built-in keys: affinity_min, rep_tier_min, flag, not_flag,
 * server_flag, not_server_flag, job_tier, level_min, time, weather. Quest registers quest_state and
 * quest_available. Must read only memory on the main thread (click handling 0.5 ms or less).
 */
@FunctionalInterface
public interface DialogCondition {

    boolean test(DialogContext ctx, DialogArgs args);

    /**
     * Checks the arguments during the content check phase. Reports problems via check.error (reload
     * rejected with line numbers).
     */
    default void validate(DialogArgs args, ArgCheck check) {}
}
