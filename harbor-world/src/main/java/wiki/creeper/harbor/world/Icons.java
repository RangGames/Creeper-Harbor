package wiki.creeper.harbor.world;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** GUI 아이콘 도우미 (이름·설명·모델). */
public final class Icons {

    private Icons() {}

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        return of(ItemStack.of(material), name, lore, null);
    }

    public static ItemStack of(
            ItemStack base, Component name, List<Component> lore, @Nullable String model) {
        ItemStack stack = base.clone();
        stack.setAmount(1);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        List<Component> lines = new ArrayList<>();
        for (Component c : lore) {
            lines.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        if (model != null && Key.parseable(model)) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        }
        return stack;
    }
}
