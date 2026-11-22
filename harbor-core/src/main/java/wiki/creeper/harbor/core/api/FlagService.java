package wiki.creeper.harbor.core.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Progress flags (e.g. climax_done, cutscene/ch1_intro). Kept in Core so that Fishing, NPC and
 * Collection, which load before Quest, can also read and write them (docs 20 0.2).
 *
 * <ul>
 *   <li>Player flags: read from the in-memory cache while online; writes go to the DB immediately.
 *       Two scopes: seasonal (reset at season rollover) and permanent
 *   <li>Server flags: progress shared by all servers (e.g. climax_done). Changes are announced to
 *       other servers over Redis
 * </ul>
 *
 * Reads and writes happen on the main thread. Changes fire PlayerFlagChangedEvent or
 * ServerFlagChangedEvent.
 */
public interface FlagService {

    /** Flag scope. */
    enum Scope {
        /** Reset at season rollover. */
        SEASON,
        /** Permanent (main story, cutscenes watched). */
        PERM
    }

    Pattern NAME = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    static String requireName(String flag) {
        if (!NAME.matcher(flag).matches()) {
            throw new IllegalArgumentException("플래그 이름 형식이 틀렸습니다: " + flag);
        }
        return flag;
    }

    boolean hasPlayer(UUID player, String flag);

    Optional<String> playerValue(UUID player, String flag);

    /**
     * Sets a player flag (value defaults to "1"). Writes to the DB even if the player is offline.
     */
    CompletableFuture<Void> setPlayer(UUID player, Scope scope, String flag, String value);

    default CompletableFuture<Void> setPlayer(UUID player, Scope scope, String flag) {
        return setPlayer(player, scope, flag, "1");
    }

    CompletableFuture<Void> unsetPlayer(UUID player, String flag);

    boolean hasServer(String flag);

    Optional<String> serverValue(String flag);

    /** Server flag. If seasonal, it is reset at season rollover. */
    CompletableFuture<Void> setServer(String flag, String value, boolean seasonal);

    CompletableFuture<Void> unsetServer(String flag);
}
