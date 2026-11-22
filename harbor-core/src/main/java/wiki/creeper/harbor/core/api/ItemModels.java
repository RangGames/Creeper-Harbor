package wiki.creeper.harbor.core.api;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ResourcePackIndex;

/**
 * Applies custom item models (item_model). Modules never set item_model directly; they go through
 * this.
 *
 * <p>A model missing from the resource pack shows up on the client as a purple-black "missing
 * model" cube. So it is set only when both conditions hold; otherwise the base item (base_item)
 * look is kept (proposed).
 *
 * <ul>
 *   <li>balance.yml {@value #SETTING} is true (the resource pack is being served). False if the key
 *       is absent
 *   <li>the served resource pack ({@link ActivePack}: the auto-built pack, otherwise
 *       resource_pack.yml {@link ResourcePackIndex}) contains that model
 * </ul>
 */
public final class ItemModels {

    /** Whether to use custom models because the resource pack is being served. */
    public static final String SETTING = "items.custom_models";

    private ItemModels() {}

    public static boolean enabled(Balance balance) {
        return balance.bool(SETTING, false);
    }

    /**
     * Whether this model may be set on an item now (enabled, valid key format, and listed in the
     * resource pack).
     */
    public static boolean available(ContentSnapshot snapshot, @Nullable String model) {
        if (model == null || !Key.parseable(model) || !enabled(snapshot.single(Balance.TYPE))) {
            return false;
        }
        return ActivePack.hasModel(snapshot, model);
    }

    /**
     * Sets item_model if the model is usable (anywhere, once Core is enabled).
     *
     * @return true if set (false if unusable; the item is left unchanged)
     */
    public static boolean apply(ItemStack stack, @Nullable String model) {
        return apply(stack, model, Harbor.api().content().snapshot());
    }

    public static boolean apply(ItemStack stack, @Nullable String model, ContentSnapshot snapshot) {
        if (model == null || !available(snapshot, model)) {
            return false;
        }
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        return true;
    }
}
