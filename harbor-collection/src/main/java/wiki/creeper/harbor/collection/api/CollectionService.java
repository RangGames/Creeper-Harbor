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
}
