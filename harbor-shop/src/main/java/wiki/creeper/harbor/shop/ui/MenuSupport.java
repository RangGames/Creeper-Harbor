package wiki.creeper.harbor.shop.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.pearl.PearlManager;

/**
 * Shared pieces of the shop screens: messages, the purchase confirmation Dialog, result reporting
 * and item icons. Main thread only.
 */
final class MenuSupport {

    private final ShopRuntime rt;

    MenuSupport(ShopRuntime rt) {
        this.rt = rt;
    }

    MessageService msg() {
        return rt.api().messages();
    }

    String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(msg().get(key));
    }

    /**
     * Purchase confirmation Dialog: price, balance, spending order and the no-refund notice
     * (disclosures required by the e-commerce act; a legal review item).
     */
    void confirm(Player player, String name, long price, Runnable onYes) {
        PearlService.Balance wallet = rt.profile(player.getUniqueId()).wallet();
        rt.api()
                .dialogs()
                .confirm(
                        player,
                        msg().get("shop.confirm.title"),
                        List.of(
                                msg().get(
                                                "shop.confirm.item",
                                                Placeholder.unparsed("name", name),
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(price))),
                                msg().get(
                                                "shop.confirm.balance",
                                                Placeholder.unparsed(
                                                        "pearls", String.valueOf(wallet.total())),
                                                Placeholder.unparsed(
                                                        "after",
                                                        String.valueOf(wallet.total() - price))),
                                msg().get("shop.confirm.order"),
                                msg().get("shop.confirm.no_refund")),
                        msg().get("shop.confirm.yes"),
                        msg().get("shop.confirm.no"),
                        yes -> {
                            if (yes) {
                                onYes.run();
                            }
                        });
    }

    void report(Player player, CompletableFuture<Optional<String>> result) {
        var _ =
                result.thenAccept(
                        error -> {
                            if (error.isPresent()) {
                                msg().send(player, error.get());
                            } else {
                                msg().send(player, "shop.bought");
                                player.closeInventory();
                            }
                        });
    }

    static Optional<String> errorKey(Result<?, PearlManager.Failure> result) {
        return switch (result) {
            case Result.Ok<?, PearlManager.Failure> ok -> Optional.empty();
            case Result.Err<?, PearlManager.Failure>(PearlManager.Failure f) ->
                    Optional.of(f.messageKey());
        };
    }

    ItemStack cosmeticIcon(
            CosmeticDef def,
            @Nullable Component line1,
            @Nullable Component line2,
            @Nullable Component line3) {
        TagResolver name = Placeholder.unparsed("name", def.name());
        Component title = msg().get("item.name." + def.grade().key(), name);
        List<Component> lore = new ArrayList<>();
        lore.add(msg().get("shop.category." + def.category().key()));
        lore.addAll(lines(def.lore(), line1, line2));
        if (line3 != null) {
            lore.add(line3);
        }
        return icon(
                def.model() != null ? "minecraft:paper" : defaultIcon(def.category()),
                def.model(),
                title,
                lore);
    }

    private static String defaultIcon(CosmeticDef.Category category) {
        return switch (category) {
            case ROD_SKIN -> "minecraft:fishing_rod";
            case LINE_FX -> "minecraft:string";
            case CATCH_FX -> "minecraft:firework_rocket";
            case BOAT_SKIN -> "minecraft:oak_boat";
            case PET -> "minecraft:egg";
            case TITLE_FRAME, NAME_COLOR -> "minecraft:name_tag";
            case CHAT_ICON -> "minecraft:paper";
            case EMOTE -> "minecraft:armor_stand";
            case OUTFIT -> "minecraft:leather_helmet";
            case AQUARIUM_THEME -> "minecraft:glass";
            case HOUSING_DECO -> "minecraft:flower_pot";
        };
    }

    List<Component> lines(List<String> lore, @Nullable Component a, @Nullable Component b) {
        List<Component> list = new ArrayList<>();
        for (String l : lore) {
            list.add(MiniMessage.miniMessage().deserialize("<gray>" + l));
        }
        if (a != null) {
            list.add(a);
        }
        if (b != null) {
            list.add(b);
        }
        return list;
    }

    static ItemStack icon(
            String base, @Nullable String model, Component name, List<Component> lore) {
        Material material =
                Optional.ofNullable(Material.matchMaterial(base)).orElse(Material.PAPER);
        ItemStack stack = ItemStack.of(material);
        ItemModels.apply(stack, model);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(lore.stream().map(MenuSupport::noItalic).toList()));
        return stack;
    }

    static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }

    static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
