package wiki.creeper.harbor.collection.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;

/**
 * Icon helpers for the collection, records, and aquarium screens. Species icons use the species
 * model if registered, otherwise a silhouette (balance.yml items.*).
 */
final class MenuItems {

    private MenuItems() {}

    static ItemStack icon(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) {
            stack.setData(
                    DataComponentTypes.LORE,
                    ItemLore.lore(
                            lore.stream()
                                    .map(
                                            c ->
                                                    c.decorationIfAbsent(
                                                            TextDecoration.ITALIC,
                                                            TextDecoration.State.FALSE))
                                    .toList()));
        }
        if (glint) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    static ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of(), false);
    }

    static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    /** Species icon: species model if known, otherwise the silhouette model. */
    static ItemStack fish(
            HarborApi api,
            FishDef fish,
            boolean known,
            Component name,
            List<Component> lore,
            boolean glint) {
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        String baseItem = balance.string("items.fish_base_item").orElse("minecraft:cod");
        Material material =
                Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        ItemStack stack = icon(material, name, lore, glint);
        if (known) {
            ItemModels.apply(stack, fish.model());
        } else {
            ItemModels.apply(
                    stack,
                    balance.string("items.silhouette_model").orElse("harbor:fish/silhouette"));
        }
        return stack;
    }

    /** ■■■□□ bar (characters from messages collection.menu.bar_full / bar_empty). */
    static Component bar(HarborApi api, int filled, int width) {
        Component full = api.messages().get("collection.menu.bar_full");
        Component empty = api.messages().get("collection.menu.bar_empty");
        Component out = Component.empty();
        for (int i = 0; i < width; i++) {
            out = out.append(i < filled ? full : empty);
        }
        return out;
    }

    /** balance.yml collection.menu.bar_width (proposed value 10). */
    static int barWidth(HarborApi api) {
        return Math.clamp(
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .integer("collection.menu.bar_width", 10),
                1,
                30);
    }
}
