package wiki.creeper.harbor.quest.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;

/**
 * Quest GUI icon helper. Icon materials can be changed with balance.yml {@code
 * quest.book.icons.<name>} (default materials otherwise).
 */
final class MenuIcons {

    private MenuIcons() {}

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

    /** Empty glass pane that fills a slot (no name). */
    static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                        .hideTooltip(true)
                        .build());
        return stack;
    }

    /** balance.yml quest.book.icons.<key> → material. fallback if absent or invalid. */
    static Material material(Balance balance, String key, Material fallback) {
        return balance.string("quest.book.icons." + key)
                .flatMap(id -> Optional.ofNullable(Material.matchMaterial(id)))
                .orElse(fallback);
    }
}
