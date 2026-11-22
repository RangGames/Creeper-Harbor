package wiki.creeper.harbor.collection.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Codex queries (Quest conditions, Shop commemorative skin purchase conditions). Online players
 * only. Main thread.
 */
public interface CodexService {

    Optional<CodexEntry> entry(UUID player, FishId fish, CodexScope scope);

    boolean isRegistered(UUID player, FishId fish, CodexScope scope);

    /** Completion 0~100. If region is given, that region only (excluding mythic). */
    double percent(UUID player, CodexScope scope, @Nullable RegionId region);

    void open(Player viewer, UUID owner, int page);
}
