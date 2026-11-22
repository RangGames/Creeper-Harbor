package wiki.creeper.harbor.quest.api;

import java.util.List;
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
}
