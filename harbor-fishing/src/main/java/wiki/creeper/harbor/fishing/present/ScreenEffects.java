package wiki.creeper.harbor.fishing.present;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Personal screen effects ("4. Fishing presentation" 1.3, legendary bite screen effect) without a
 * datapack: a resource pack post effect added to this player only (26.3 Player#postEffects) and a
 * camera overlay shown through a client-only head item (sendEquipmentChange of an item whose
 * equippable component has camera_overlay; the real helmet is untouched and re-sent afterwards).
 * Other post effects on the player (e.g. World region ambience) are kept. Main thread only.
 */
final class ScreenEffects {

    private record Active(@Nullable Key postEffect, boolean overlay, BukkitTask end) {}

    private final Tasks tasks;
    private final Map<UUID, Active> active = new HashMap<>();

    ScreenEffects(Tasks tasks) {
        this.tasks = tasks;
    }

    void play(Player player, FishingPresets.Stage stage) {
        String effect = stage.postEffect();
        String overlay = stage.cameraOverlay();
        if (effect == null && overlay == null) {
            return;
        }
        clear(player);
        Key effectKey = effect == null ? null : Key.key(effect);
        if (effectKey != null && player.postEffects().add(effectKey)) {
            player.postEffects().update();
        }
        if (overlay != null) {
            ItemStack mask = ItemStack.of(Material.PAPER);
            mask.setData(
                    DataComponentTypes.EQUIPPABLE,
                    Equippable.equippable(EquipmentSlot.HEAD)
                            .cameraOverlay(Key.key(overlay))
                            .build());
            player.sendEquipmentChange(player, EquipmentSlot.HEAD, mask);
        }
        BukkitTask end = tasks.later(stage.effectTicks(), () -> clear(player));
        active.put(player.getUniqueId(), new Active(effectKey, overlay != null, end));
    }

    /** Removes this presentation's effects (quit, next bite). */
    void clear(Player player) {
        Active current = active.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        current.end().cancel();
        Key effect = current.postEffect();
        if (effect != null && player.isOnline() && player.postEffects().remove(effect)) {
            player.postEffects().update();
        }
        if (current.overlay() && player.isOnline()) {
            ItemStack helmet = player.getInventory().getHelmet();
            player.sendEquipmentChange(
                    player, EquipmentSlot.HEAD, helmet == null ? ItemStack.empty() : helmet);
        }
    }
}
