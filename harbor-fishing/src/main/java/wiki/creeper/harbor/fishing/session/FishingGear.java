package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Bait deduction and return, rod durability, item delivery (dropped at the feet when full). Main
 * thread only.
 */
final class FishingGear {

    private final HarborApi api;
    private final Tasks tasks;
    private final CastContext ctx;
    private final NamespacedKey baitUses;

    FishingGear(Plugin plugin, HarborApi api, Tasks tasks, CastContext ctx) {
        this.api = api;
        this.tasks = tasks;
        this.ctx = ctx;
        this.baitUses = new NamespacedKey(plugin, "bait_uses");
    }

    // ------------------------------------------------------------------ Bait · rod

    boolean isLegendBait(@Nullable ItemId bait) {
        if (bait == null) {
            return false;
        }
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, bait.id())
                .map(d -> d.stats().getOrDefault("legend_only", 0.0) > 0)
                .orElse(false);
    }

    /**
     * Deducts 1 off-hand bait. For bait with uses (shiny lure uses), decrements the remaining uses
     * by 1 and removes 1 item when it reaches 0.
     */
    void consumeBait(Player player, @Nullable ItemId bait) {
        if (bait == null) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        Optional<ItemDef> def = ctx.itemDef(offhand).filter(d -> d.id().equals(bait));
        if (def.isEmpty()) {
            return;
        }
        int uses = (int) Math.round(def.get().stats().getOrDefault("uses", 0.0));
        if (uses > 1) {
            Integer stored =
                    offhand.getPersistentDataContainer().get(baitUses, PersistentDataType.INTEGER);
            int left = (stored == null ? uses : stored) - 1;
            if (left > 0) {
                offhand.editPersistentDataContainer(
                        pdc -> pdc.set(baitUses, PersistentDataType.INTEGER, left));
                api.ui()
                        .actionBar(
                                player,
                                api.messages()
                                        .get(
                                                "fishing.bait_uses",
                                                Placeholder.unparsed(
                                                        "left", String.valueOf(left))));
                return;
            }
            offhand.editPersistentDataContainer(pdc -> pdc.remove(baitUses));
        }
        offhand.setAmount(offhand.getAmount() - 1);
    }

    void refundBait(Player player, ItemId bait) {
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, bait.id())
                .ifPresent(def -> give(player, api.items().create(def, 1)));
    }

    /** Fishing is not allowed when durability is 1 or less (repair notice). */
    static boolean rodBroken(ItemStack rod) {
        if (rod.isEmpty()) {
            return false;
        }
        Integer max = rod.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = rod.getData(DataComponentTypes.DAMAGE);
        return max != null && max > 0 && damage != null && damage >= max - 1;
    }

    /** Durability −1 (stops before reaching 0: it does not break, a repair notice is shown). */
    void damageRod(Player player) {
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (rod.isEmpty() || rod.getType().getMaxDurability() <= 0 || rodBroken(rod)) {
            return;
        }
        Integer max = rod.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = rod.getData(DataComponentTypes.DAMAGE);
        if (max != null && damage != null && damage + 1 >= max - 1) {
            rod.setData(DataComponentTypes.DAMAGE, max - 1);
            api.messages().send(player, "fishing.rod_broken");
            return;
        }
        player.damageItemStack(EquipmentSlot.HAND, 1);
    }

    /**
     * If the inventory is full, drops it at the feet and lets only the owner pick it up for 60
     * seconds.
     */
    void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            Item dropped = player.getWorld().dropItem(loc(player), left);
            dropped.setOwner(player.getUniqueId());
            var _ =
                    tasks.later(
                            60 * 20,
                            () -> {
                                if (dropped.isValid()) {
                                    dropped.setOwner(null);
                                }
                            });
        }
    }
}
