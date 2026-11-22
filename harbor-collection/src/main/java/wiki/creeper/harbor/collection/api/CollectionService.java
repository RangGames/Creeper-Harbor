package wiki.creeper.harbor.collection.api;

import java.util.Optional;
import java.util.UUID;
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
