package wiki.creeper.harbor.core.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.UseRemainder;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.ItemComponents;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.ItemModels;

/**
 * Applies an item definition's {@code components:} block with the Paper Data Component API ('8.
 * System design' 2 item table, docs 19 5.5 per-type components). Blocks that are absent keep the
 * base item's own component (e.g. cooked_cod already has food and consumable).
 *
 * <p>Resource-pack assets (equippable camera_overlay and asset_id) are set only when the item's own
 * model is in the served pack ({@link ItemModels#available}); a missing overlay texture would cover
 * the screen with the missing-texture pattern (proposed rule: the pack that ships the model ships
 * its overlay and equipment model too).
 */
final class ItemComponentApplier {

    private ItemComponentApplier() {}

    static void apply(ItemStack stack, ItemDef def, ContentSnapshot snapshot) {
        ItemComponents components = def.components();
        if (components.isEmpty()) {
            return;
        }
        ItemComponents.Consumable consumable = components.consumable();
        if (consumable != null) {
            Consumable.Builder builder =
                    Consumable.consumable()
                            .consumeSeconds((float) consumable.seconds())
                            .animation(ItemUseAnimation.valueOf(consumable.animation().name()))
                            .hasConsumeParticles(consumable.particles());
            String sound = consumable.sound();
            if (sound != null) {
                builder.sound(Key.key(sound));
            }
            stack.setData(DataComponentTypes.CONSUMABLE, builder.build());
        }
        ItemComponents.Food food = components.food();
        if (food != null) {
            stack.setData(
                    DataComponentTypes.FOOD,
                    FoodProperties.food()
                            .nutrition(food.nutrition())
                            .saturation((float) food.saturation())
                            .canAlwaysEat(food.canAlwaysEat())
                            .build());
        }
        String remainder = components.useRemainder();
        if (remainder != null) {
            Material material = Material.matchMaterial(remainder);
            if (material != null && material.isItem()) {
                stack.setData(
                        DataComponentTypes.USE_REMAINDER,
                        UseRemainder.useRemainder(ItemStack.of(material)));
            }
        }
        ItemComponents.UseCooldown cooldown = components.useCooldown();
        if (cooldown != null) {
            UseCooldown.Builder builder = UseCooldown.useCooldown((float) cooldown.seconds());
            String group = cooldown.group();
            if (group != null) {
                builder.cooldownGroup(Key.key(group));
            }
            stack.setData(DataComponentTypes.USE_COOLDOWN, builder.build());
        }
        ItemComponents.Equippable equippable = components.equippable();
        if (equippable != null) {
            EquipmentSlot slot = slot(equippable.slot());
            // Start from the base item's own equippable (armor keeps its vanilla look and sound
            // when the pack assets are not available)
            Equippable base = stack.getData(DataComponentTypes.EQUIPPABLE);
            Equippable.Builder builder =
                    base != null && base.slot() == slot
                            ? base.toBuilder()
                            : Equippable.equippable(slot);
            if (ItemModels.available(snapshot, def.model())) {
                String overlay = equippable.cameraOverlay();
                if (overlay != null) {
                    builder.cameraOverlay(Key.key(overlay));
                }
                String asset = equippable.assetId();
                if (asset != null) {
                    builder.assetId(Key.key(asset));
                }
            }
            stack.setData(DataComponentTypes.EQUIPPABLE, builder.build());
        }
        components
                .maxStack()
                .ifPresent(
                        max -> {
                            // Damageable items cannot stack (vanilla rejects the item)
                            if (max == 1 || !stack.hasData(DataComponentTypes.MAX_DAMAGE)) {
                                stack.setData(DataComponentTypes.MAX_STACK_SIZE, max);
                            }
                        });
    }

    private static EquipmentSlot slot(ItemComponents.Slot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case MAINHAND -> EquipmentSlot.HAND;
            case OFFHAND -> EquipmentSlot.OFF_HAND;
            case BODY -> EquipmentSlot.BODY;
            case SADDLE -> EquipmentSlot.SADDLE;
        };
    }
}
