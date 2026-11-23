package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Collection queries (Quest conditions, Shop commemorative skin purchase conditions). Online
 * players only. Main thread.
 */
public interface CollectionService {

    Optional<CollectionEntry> entry(UUID player, FishId fish, CollectionScope scope);

    boolean isRegistered(UUID player, FishId fish, CollectionScope scope);

    /**
     * Stored collection cells of any player, online or not, read from the DB (read-only, for
     * external readers such as the Harbor-Web REST API). SEASON is the current season. For an
     * online player, catches not yet written by the write-behind queue may be missing. Sorted by
     * fish ID; completes on the main thread.
     */
    CompletableFuture<List<CollectionCell>> storedEntries(UUID player, CollectionScope scope);

    /** Completion 0~100. If region is given, that region only (excluding mythic). */
    double percent(UUID player, CollectionScope scope, @Nullable RegionId region);

    void open(Player viewer, UUID owner, int page);

    /**
     * Admin: registers or overwrites a collection cell like {@code /harbor collection entry set}
     * (season and total, as a catch would; size clamped to the species range, default the minimum
     * size and ★1). Fires the usual registration events. False if the species is unknown or the
     * player is not loaded on this server.
     */
    boolean registerEntry(
            UUID player, FishId fish, @Nullable Double size, @Nullable Integer quality);

    /**
     * Admin: deletes this season's collection cell like {@code /harbor collection entry remove}.
     * False if the player is not loaded on this server.
     */
    boolean removeEntry(UUID player, FishId fish);
}
