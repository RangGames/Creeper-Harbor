package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** Data of online players. */
public interface PlayerService {

    Optional<HarborPlayer> get(UUID player);

    /**
     * Finds a player by name (case-insensitive, proposed). Checks players on this server first,
     * then the players table (players on other servers and offline; the account with the most
     * recent login). Completes on the main thread.
     */
    CompletableFuture<Optional<wiki.creeper.harbor.common.model.PlayerRef>> find(String name);

    /**
     * Data of an online player. Loading finishes before login, so it is always present while
     * online.
     */
    HarborPlayer get(Player player);

    Collection<? extends HarborPlayer> online();

    /** Registers module data. Called from the module's onEnable (before the first login). */
    void register(PlayerComponent<?> component);

    /**
     * Writes this player's changed data (base info + module data) to the DB now. For places that
     * must save immediately, like auction listings or boss rewards (flushes only this player's
     * entries from the write-behind queue). Call on the main thread; the future completes after
     * commit. Completes immediately if the player is not online.
     */
    CompletableFuture<Void> saveNow(UUID player);

    /**
     * Calls {@link #saveNow} after a short delay (balance.yml {@code
     * inventory.commit_save_delay_ticks}). Callable from any thread; multiple calls within the
     * delay save only once. Call after an immediately committed transaction such as currency,
     * auction or mail: the inventory is write-behind, so if the server crashes in between, the
     * items revert while the transaction result remains, causing duplication. The delay gives time
     * for the transaction wrap-up (item grant/removal on the main thread) to finish. Does nothing
     * if the player is not on this server or is transferring between servers (proposed).
     */
    void saveSoon(UUID player);

    /**
     * Whether the server-transfer snapshot has already been taken (main thread). If true, changes
     * to the player's state on this server won't be saved: when handling the quit, don't return
     * items to the inventory or move them to the DB; leave them as they are or send them by mail.
     * Core blocks the player's actions during this time.
     */
    boolean frozen(UUID player);
}
