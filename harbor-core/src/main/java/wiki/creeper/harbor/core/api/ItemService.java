package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;

/** Item creation and reading. Used on the main thread. */
public interface ItemService {

    ItemStack create(ItemDef def, int amount);

    /**
     * Creates a fish item. ★5, legendary and mythic fish get a unique ID recorded in fish_ledger
     * (asynchronously).
     *
     * @param sizeCm size (stored to one decimal place)
     */
    ItemStack createFish(
            FishDef def, double sizeCm, Quality quality, PlayerRef catcher, Instant caughtAt);

    /** Empty Optional if not a Harbor item. Old format versions are read as the new format. */
    Optional<HarborItem> read(ItemStack stack);

    /**
     * If the item uses an old format version, rewrites custom_data to the new format. True if
     * rewritten.
     */
    boolean upgrade(ItemStack stack);
}
