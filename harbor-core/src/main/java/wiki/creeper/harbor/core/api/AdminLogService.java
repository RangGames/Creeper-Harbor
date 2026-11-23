package wiki.creeper.harbor.core.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Admin action audit log (the admin_log table, proposed API). {@code /harbor} admin commands record
 * their own entries; this service is for admin actions that have no command sender: Skript {@code
 * harbor admin ...} effects (actor {@code skript}), web or other plugin integrations.
 *
 * <p>Module admin API methods (QuestService {@code admin*}, JobsService {@code admin*} and the
 * like) do not write the log themselves, so the same action is not recorded twice when a command
 * calls them. A caller that invokes them outside a command must record the action here.
 *
 * <p>Callable from any thread; the write runs asynchronously and a failure is only logged. Values
 * longer than the columns are truncated (actor 36, action 64, target 64 characters).
 */
public interface AdminLogService {

    /**
     * Records one admin action.
     *
     * @param actor who did it: a player UUID, {@code CONSOLE}, or a short system name such as
     *     {@code skript} / {@code api} / {@code web}
     * @param action stable key, {@code <source>.<what>} (e.g. {@code skript.complete_quest})
     * @param target affected player, if any
     * @param detail free text (arguments, script name and line)
     */
    void record(String actor, String action, @Nullable UUID target, String detail);
}
