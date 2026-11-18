package wiki.creeper.harbor.season.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** GUI 아이콘 도우미 (데이터 컴포넌트). */
final class Icons {

    private Icons() {}

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

    static ItemStack head(@Nullable UUID owner, Component name, List<Component> lore) {
        ItemStack stack = icon(Material.PLAYER_HEAD, name, lore, false);
        if (owner != null) {
            stack.setData(
                    DataComponentTypes.PROFILE,
                    ResolvableProfile.resolvableProfile().uuid(owner).build());
        }
        return stack;
    }

    static ItemStack model(ItemStack stack, @Nullable String model) {
        if (model != null) {
            try {
                stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
            } catch (IllegalArgumentException e) {
                // 형식이 틀린 모델 이름은 기본 모양으로 둔다
            }
        }
        return stack;
    }
}
