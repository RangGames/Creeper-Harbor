package wiki.creeper.harbor.wildlife.api;

import org.jspecify.annotations.Nullable;

/**
 * Behaviour for {@link WildlifeService#spawnScripted} (for events and cinematics).
 *
 * @param action action to play once at the start (name from behaviours.yml actions, e.g. leap).
 *     null for none
 * @param hold if true, stays in place (no CRUISE target; only idle and special actions in place)
 * @param lifetimeSeconds disappears after this time. If 0 or less, follows only the normal cleanup
 *     rules like other fish (56 blocks, chunk unload)
 * @param ignoreThreat if true, does not react to threats (so it doesn't flee mid-cinematic)
 */
public record ScriptedBehaviour(
        @Nullable String action, boolean hold, int lifetimeSeconds, boolean ignoreThreat) {

    /** Swims like a normal fish (no lifetime). */
    public static final ScriptedBehaviour CRUISE = new ScriptedBehaviour(null, false, 0, false);

    /** Shows one action in place and stays for a set time. */
    public static ScriptedBehaviour show(@Nullable String action, int seconds) {
        return new ScriptedBehaviour(action, true, seconds, true);
    }
}
