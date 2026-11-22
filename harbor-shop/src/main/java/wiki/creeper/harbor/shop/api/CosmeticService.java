package wiki.creeper.harbor.shop.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.CosmeticDef;

/**
 * Cosmetic ownership and equipping (docs 20 12.2). Ownership is an account record
 * (cosmetics_owned), so it is not subject to season resets or trading. One equip slot per category.
 *
 * <p>Rod skins only change the item's item_model and write harbor:skin (skin ID, owner) into
 * custom_data (PDC). On auction listing, trading, or pickup by someone else, Economy and others
 * revert to the original model with {@link #stripSkin}. Performance values (stats) are not touched.
 *
 * <p>Methods reading the cache are main-thread only, and futures complete on the main thread.
 */
public interface CosmeticService {

    /** Whether an online player owns it (cache). */
    boolean owns(UUID player, HarborId cosmetic);

    /** Owned list of an online player (cache). */
    Set<HarborId> owned(UUID player);

    /** Equipped cosmetics (online, cache). */
    Optional<HarborId> equipped(UUID player, CosmeticDef.Category slot);

    /**
     * Grants it (regardless of whether online, immediate write). false if already owned.
     *
     * @param source cosmetics_owned.source (PASS · CODEX · FESTIVAL · ADMIN …)
     */
    CompletableFuture<Boolean> grant(UUID player, HarborId cosmetic, String source, String refId);

    /** Revokes it (admin). Also unequips if equipped. true if it was owned. */
    CompletableFuture<Boolean> revoke(UUID player, HarborId cosmetic);

    /** Equips it. false if not owned. */
    boolean equip(Player player, HarborId cosmetic);

    /** Clears that slot. */
    void unequip(Player player, CosmeticDef.Category slot);

    /** Rod skin ID applied to the item. */
    Optional<HarborId> skinOf(ItemStack stack);

    /** Strips the rod skin and reverts to the original model. true if there was a skin. */
    boolean stripSkin(ItemStack stack);

    /**
     * Registers a condition for hiding pets and boat decorations (e.g. Boss: players inside a boss
     * arena). Hidden if any returns true. Removed when owner is disabled. Called on the main
     * thread.
     */
    void registerPetSuppressor(Plugin owner, Predicate<Player> hide);
}
