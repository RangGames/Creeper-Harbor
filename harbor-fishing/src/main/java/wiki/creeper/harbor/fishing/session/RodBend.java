package wiki.creeper.harbor.fishing.session;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Rod tension model ("4. Fishing presentation & boss fights" 1.3, range_dispatch on
 * custom_model_data float): while fighting, float 0 of the held rod is set to the tension (0~1)
 * quantized to a few levels so the resource pack can swap in a more bent rod model. Only changes
 * when the level changes (each change re-sends the held item). The rod's original custom_model_data
 * is restored when the fight ends. Main thread only.
 */
final class RodBend {

    private int slot = -1;
    private int level = -1;
    private @Nullable CustomModelData original;

    /**
     * @param steps number of levels (0 = off)
     */
    void update(Player player, double tension, int steps) {
        if (steps <= 0) {
            return;
        }
        int next = (int) Math.round(Math.clamp(tension, 0, 1) * steps);
        if (next == level) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        if (slot >= 0 && held != slot) {
            return; // Slot changes are blocked during the fight; ignore if it happens anyway
        }
        ItemStack rod = inventory.getItem(held);
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }
        if (slot < 0) {
            slot = held;
            original = rod.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        }
        level = next;
        CustomModelData base = original;
        CustomModelData.Builder data =
                CustomModelData.customModelData().addFloat((float) next / steps);
        if (base != null) {
            // Keep the rod's own data after float 0
            data.addFloats(
                            base.floats()
                                    .subList(
                                            Math.min(1, base.floats().size()),
                                            base.floats().size()))
                    .addFlags(base.flags())
                    .addStrings(base.strings())
                    .addColors(base.colors());
        }
        rod.setData(DataComponentTypes.CUSTOM_MODEL_DATA, data.build());
        inventory.setItem(held, rod);
    }

    /** Restores the rod (fight end, quit). */
    void reset(Player player) {
        if (slot < 0) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack rod = inventory.getItem(slot);
        if (rod != null && rod.getType() == Material.FISHING_ROD) {
            CustomModelData base = original;
            if (base != null) {
                rod.setData(DataComponentTypes.CUSTOM_MODEL_DATA, base);
            } else {
                rod.resetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            }
            inventory.setItem(slot, rod);
        }
        slot = -1;
        level = -1;
        original = null;
    }
}
