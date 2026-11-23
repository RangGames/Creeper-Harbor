package wiki.creeper.harbor.quest.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Harbor-Quest public API (docs 20 6.2). Registered in the ServicesManager. Use it on the main
 * thread; futures complete on the main thread.
 *
 * <pre>{@code
 * QuestService quests = Harbor.api().service(QuestService.class).orElseThrow();
 * if (quests.isCompleted(uuid, QuestId.parse("quest/main/ch3_kept_promise"))) { ... }
 * }</pre>
 */
public interface QuestService {

    /** Includes computed states (LOCKED, AVAILABLE). LOCKED if the player is not online. */
    QuestState state(UUID player, QuestId quest);

    /** Quests in progress (ACTIVE, READY, COMPLETING). */
    List<QuestView> active(UUID player);

    /** Whether it was completed in this cycle (main quests regardless of season). */
    boolean isCompleted(UUID player, QuestId quest);

    /**
     * Stored quest rows of any player, online or not, read from the DB (read-only, for external
     * readers such as the Harbor-Web REST API): this season's rows plus main quests carried over
     * from earlier seasons, one per quest (latest season's row), sorted by quest ID. For an online
     * player, progress not yet written by the write-behind queue may lag. Safe to call from any
     * thread; completes on the main thread.
     */
    CompletableFuture<List<QuestRecord>> storedQuests(UUID player);

    /** Checks the requirements again and accepts. Failure reasons are sent in chat. */
    CompletableFuture<Boolean> accept(Player player, QuestId quest);

    /** Abandons (not allowed for main quests). */
    CompletableFuture<Boolean> abandon(Player player, QuestId quest);

    /**
     * Turns in (READY only). Check and remove deliver items → complete → rewards. Job advancement
     * quests open the Jobs advancement screen.
     */
    CompletableFuture<Boolean> turnIn(Player player, QuestId quest);

    /** Changes the tracked quest (action bar and location marker). */
    boolean track(Player player, QuestId quest);

    /** The tracked quest (empty if none or the player is not online). */
    Optional<QuestId> tracked(UUID player);

    /** Stops tracking. False if nothing was tracked. */
    boolean untrack(Player player);

    // The admin* methods do not write admin_log (commands and Harbor-Skript record their own
    // entries). Other callers must record the action via Harbor.api().adminLog().

    /**
     * Admin: completes a quest like {@code /harbor quest complete}. A quest in progress goes
     * through the normal two-phase completion (COMPLETING → rewards → COMPLETED, without rewards if
     * reward is false); any other quest only gets the COMPLETED state, with no rewards.
     */
    CompletableFuture<Boolean> adminComplete(Player player, QuestId quest, boolean reward);

    /** Admin: deletes the quest row like {@code /harbor quest reset} (main quests too). */
    CompletableFuture<Boolean> adminReset(Player player, QuestId quest);

    /**
     * Admin: forces a state like {@code /harbor quest set} (ACTIVE · READY · COMPLETED · FAILED
     * only, no rewards). Creates a current-season row if there is none. False for other states.
     */
    CompletableFuture<Boolean> adminSetState(Player player, QuestId quest, QuestState state);

    /**
     * Admin: sets one objective progress value like {@code /harbor quest progress}. False if the
     * quest is not in progress or the objective key is unknown.
     */
    boolean adminSetProgress(Player player, QuestId quest, String objective, int value);

    /**
     * The current cycle's request board (daily · weekly · collect) if it was already drawn. Empty
     * if not drawn yet, the kind is unknown or the player is not online.
     */
    Optional<BoardView> board(UUID player, String kind);

    /**
     * The current cycle's request board, drawing and saving it first if needed (what opening the
     * board does). Accept a request with {@link #accept}. Empty for an unknown kind.
     */
    CompletableFuture<Optional<BoardView>> drawBoard(Player player, String kind);

    /** Admin: draws a new board for the current cycle like {@code /harbor quest board reroll}. */
    CompletableFuture<Optional<BoardView>> adminRerollBoard(Player player, String kind);
}
