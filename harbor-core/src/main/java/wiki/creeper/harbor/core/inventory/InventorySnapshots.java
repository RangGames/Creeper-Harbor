package wiki.creeper.harbor.core.inventory;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * Applies and captures {@link InventoryData} snapshots of a live player (split out of {@link
 * InventorySync}). Main thread only.
 */
final class InventorySnapshots {

    static final int CRAFTING_SLOTS = 5;

    private InventorySnapshots() {}

    /**
     * Follows HuskSync BukkitData's apply order: clear crafting grid and cursor → inventory → ender
     * chest → PDC → effects → health, food, experience.
     */
    static void apply(Player player, InventoryData data) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            for (int i = 0; i < Math.min(CRAFTING_SLOTS, top.getSize()); i++) {
                top.setItem(i, null);
            }
        }
        player.setItemOnCursor(null);
        player.getInventory()
                .setContents(
                        fit(
                                ItemStack.deserializeItemsFromBytes(data.contents()),
                                player.getInventory().getSize()));
        Inventory ender = player.getEnderChest();
        ender.setContents(fit(ItemStack.deserializeItemsFromBytes(data.ender()), ender.getSize()));
        try {
            player.getPersistentDataContainer().readFromBytes(data.pdc(), true);
        } catch (IOException e) {
            throw new IllegalStateException("PDC를 읽지 못했습니다", e);
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT);
        for (InventoryData.Effect e : data.effects()) {
            PotionEffectType type =
                    Key.parseable(e.type()) ? registry.get(Key.key(e.type())) : null;
            if (type != null) {
                player.addPotionEffect(
                        new PotionEffect(
                                type,
                                e.duration(),
                                e.amplifier(),
                                e.ambient(),
                                e.particles(),
                                e.icon()));
            }
        }
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max == null ? 20.0 : max.getValue();
        double health = data.health() <= 0 ? maxHealth : Math.min(data.health(), maxHealth);
        player.setHealth(health);
        player.setFoodLevel(data.food());
        player.setSaturation(data.saturation());
        player.setExhaustion(data.exhaustion());
        player.setTotalExperience(data.totalExp());
        player.setLevel(data.level());
        if (data.exp() >= 0f && data.exp() <= 1f) {
            player.setExp(data.exp());
        }
        if (data.heldSlot() >= 0 && data.heldSlot() <= 8) {
            player.getInventory().setHeldItemSlot(data.heldSlot());
        }
        player.updateInventory();
    }

    /**
     * Adjusts if the saved slot count differs from the current size (pads with empty slots if
     * short, truncates if over).
     */
    private static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            out[i] = i < items.length ? items[i] : ItemStack.empty();
        }
        return out;
    }

    static InventoryData capture(Player player) {
        return capture(player, List.of());
    }

    /**
     * Captures the current state. {@code outside} is put only into the snapshot, into empty
     * inventory slots (ender chest if full); the real inventory is not changed. If both are full,
     * the remainder is left out of the snapshot (only logged).
     */
    static InventoryData capture(Player player, List<ItemStack> outside) {
        ItemStack[] contents = nonNull(player.getInventory().getContents());
        ItemStack[] ender = nonNull(player.getEnderChest().getContents());
        if (!outside.isEmpty()) {
            contents = copy(contents);
            ender = copy(ender);
            for (ItemStack item : outside) {
                ItemStack rest = place(contents, STORAGE_SLOTS, item.clone());
                if (!rest.isEmpty()) {
                    rest = place(ender, ender.length, rest);
                }
                if (!rest.isEmpty()) {
                    System.getLogger("Harbor/InventorySync")
                            .log(
                                    System.Logger.Level.WARNING,
                                    "저장본에 넣을 자리가 없어 맡은 아이템을 빼고 저장합니다: "
                                            + player.getName()
                                            + " "
                                            + rest);
                }
            }
        }
        List<InventoryData.Effect> effects = new ArrayList<>();
        for (PotionEffect e : player.getActivePotionEffects()) {
            if (e.isAmbient()) {
                continue; // Beacon and conduit effects stay where they are (same as HuskSync)
            }
            effects.add(
                    new InventoryData.Effect(
                            e.getType().key().asString(),
                            e.getDuration(),
                            e.getAmplifier(),
                            false,
                            e.hasParticles(),
                            e.hasIcon()));
        }
        return new InventoryData(
                ItemStack.serializeItemsAsBytes(contents),
                ItemStack.serializeItemsAsBytes(ender),
                serializePdc(player),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getInventory().getHeldItemSlot(),
                effects);
    }

    private static byte[] serializePdc(Player player) {
        try {
            return player.getPersistentDataContainer().serializeToBytes();
        } catch (IOException e) {
            throw new IllegalStateException("PDC를 저장하지 못했습니다", e);
        }
    }

    /**
     * Storage slot count of the player inventory (including hotbar, excluding armor and offhand).
     */
    private static final int STORAGE_SLOTS = 36;

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i].clone();
        }
        return out;
    }

    /**
     * Fills matching stacks first, then empty slots (same rules as addItem). Returns the remainder
     * (empty item if all fit).
     */
    private static ItemStack place(ItemStack[] slots, int limit, ItemStack item) {
        int left = item.getAmount();
        int max = item.getMaxStackSize();
        int end = Math.min(limit, slots.length);
        for (int i = 0; i < end && left > 0; i++) {
            ItemStack slot = slots[i];
            if (!slot.isEmpty() && slot.isSimilar(item) && slot.getAmount() < max) {
                int n = Math.min(left, max - slot.getAmount());
                slot.setAmount(slot.getAmount() + n);
                left -= n;
            }
        }
        for (int i = 0; i < end && left > 0; i++) {
            if (slots[i].isEmpty()) {
                int n = Math.min(left, max);
                ItemStack put = item.clone();
                put.setAmount(n);
                slots[i] = put;
                left -= n;
            }
        }
        if (left <= 0) {
            return ItemStack.empty();
        }
        ItemStack rest = item.clone();
        rest.setAmount(left);
        return rest;
    }

    private static ItemStack[] nonNull(@Nullable ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            out[i] = item == null ? ItemStack.empty() : item;
        }
        return out;
    }
}
