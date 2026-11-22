package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.TitleDef;

/**
 * Title store (titles, docs 20 0.1, 11.4). Collection, Quest, Boss and Shop only request grants.
 * Titles are not reset by season.
 *
 * <ul>
 *   <li>{@link #grant} prevents duplicates via the (uuid, title_id) unique key and treats an
 *       existing title as success with {@link Grant#ALREADY_OWNED} (idempotent). Writes to the DB
 *       immediately even if the player is offline.
 *   <li>Titles not in titles.yml are rejected with {@link Grant#UNKNOWN_TITLE}. When content refers
 *       to a title, registering a reference check with {@code ctx.ref("title", id, pos)} is
 *       recommended.
 *   <li>All result futures complete on the main thread.
 * </ul>
 *
 * <pre>{@code
 * // Collection: on reaching a collection milestone (Season does not listen to CollectionMilestoneEvent, so call directly)
 * titles.grant(uuid, TitleId.of(milestone.title()), "codex:" + season + ":" + milestone.percent());
 * }</pre>
 */
public interface TitleService {

    /** Grant result. */
    enum Grant {
        GRANTED,
        ALREADY_OWNED,
        UNKNOWN_TITLE,
        FAILED;

        /** Whether the title is now owned (newly granted or already owned). */
        public boolean owned() {
            return this == GRANTED || this == ALREADY_OWNED;
        }
    }

    /**
     * Grants a title.
     *
     * @param refId source reference ID (for records, 1-64 characters, e.g. {@code
     *     hof:3:biggest_fish:fish/x}, {@code worldboss:<event>:<uuid>})
     */
    CompletableFuture<Grant> grant(UUID player, TitleId title, @Nullable String refId);

    /**
     * Revokes a title (admin, end of donation etc.). Also unequips it if equipped. true if it was
     * owned.
     */
    CompletableFuture<Boolean> revoke(UUID player, TitleId title);

    /** Owned titles (read from the DB, in order obtained). */
    CompletableFuture<List<OwnedTitle>> list(UUID player);

    /** Whether an online player owns the title (memory cache, main thread). false if not online. */
    boolean has(UUID player, TitleId title);

    /** Checks via the DB regardless of whether the player is online. */
    CompletableFuture<Boolean> hasAsync(UUID player, TitleId title);

    /** Equipped title (online player, main thread). */
    Optional<TitleId> equipped(UUID player);

    /**
     * Equips a title (same value as the title slot in the Shop equipment screen). null unequips.
     * false if the title is not owned. Online players only, main thread.
     */
    boolean equip(UUID player, @Nullable TitleId title);

    /** Title definitions (titles.yml). */
    Optional<TitleDef> definition(TitleId title);

    /** Display text (titles.yml name, MiniMessage). The ID if there is no definition. */
    Component display(TitleId title);

    /**
     * Display text of the equipped title (for prefixing chat and name tags). Empty Optional if none
     * equipped.
     */
    default Optional<Component> equippedDisplay(UUID player) {
        return equipped(player).map(this::display);
    }
}
