package wiki.creeper.harbor.fishing.api;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * Result of {@link FishingService#createCatch}.
 *
 * @param stack the created item (null for release_only species; the caller hands it out)
 * @param fish item info (size, quality, unique ID)
 * @param capped whether it hit the job quality cap
 */
public record CatchResult(@Nullable ItemStack stack, HarborItem.Fish fish, boolean capped) {}
