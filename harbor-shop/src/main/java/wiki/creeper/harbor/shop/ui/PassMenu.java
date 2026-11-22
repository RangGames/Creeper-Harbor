package wiki.creeper.harbor.shop.ui;

import static wiki.creeper.harbor.shop.ui.MenuSupport.named;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.PassDef;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pass.PassMath;
import wiki.creeper.harbor.shop.util.UuidV7;

/** The season pass screen (/패스): reward levels plus premium and level purchase buttons. */
final class PassMenu {

    private final ShopRuntime rt;
    private final PassManager passes;
    private final MenuSupport ui;

    PassMenu(ShopRuntime rt, PassManager passes, MenuSupport ui) {
        this.rt = rt;
        this.passes = passes;
        this.ui = ui;
    }

    void open(Player player, int page) {
        Optional<PassDef> found = rt.currentPass();
        if (found.isEmpty()) {
            ui.msg().send(player, "shop.pass.none");
            return;
        }
        PassDef def = found.get();
        UUID uuid = player.getUniqueId();
        ShopProfile.Pass pass = rt.profile(uuid).pass();
        int level = PassMath.level(def, pass.exp(), pass.bought());
        List<Integer> levels = new ArrayList<>();
        for (int i = 1; i <= def.levels(); i++) {
            levels.add(i);
        }
        PagedMenu<Integer> menu =
                new PagedMenu<>(
                        ui.msg()
                                .get(
                                        "shop.pass.title",
                                        Placeholder.unparsed("name", def.name()),
                                        Placeholder.unparsed("level", String.valueOf(level)),
                                        Placeholder.unparsed("max", String.valueOf(def.levels()))),
                        6,
                        levels,
                        l -> new PagedMenu.Entry(levelIcon(def, l, level, pass), e -> {}),
                        named(Material.ARROW, ui.msg().get("shop.menu.previous")),
                        named(Material.ARROW, ui.msg().get("shop.menu.next")));
        menu.show(player, page);
        int nav = menu.size() - 9;
        menu.button(
                nav + 2,
                named(
                        Material.EXPERIENCE_BOTTLE,
                        ui.msg()
                                .get(
                                        "shop.pass.progress",
                                        Placeholder.unparsed(
                                                "exp",
                                                String.valueOf(
                                                        PassMath.progress(
                                                                def, pass.exp(), pass.bought()))),
                                        Placeholder.unparsed(
                                                "need", String.valueOf(def.expPerLevel())))),
                null);
        if (pass.premium() < PassMath.PREMIUM) {
            UUID order = UuidV7.next();
            menu.button(
                    nav + 3,
                    named(
                            Material.GOLD_INGOT,
                            ui.msg()
                                    .get(
                                            "shop.pass.buy_premium",
                                            Placeholder.unparsed(
                                                    "price",
                                                    String.valueOf(def.prices().premium())))),
                    e ->
                            ui.confirm(
                                    player,
                                    ui.plain("shop.pass.premium"),
                                    def.prices().premium(),
                                    () ->
                                            ui.report(
                                                    player,
                                                    passes.buyPremium(
                                                            player, PassMath.PREMIUM, order))));
        }
        if (pass.premium() < PassMath.PREMIUM_PLUS) {
            UUID order = UuidV7.next();
            long price = PassMath.premiumPrice(def, pass.premium(), PassMath.PREMIUM_PLUS);
            menu.button(
                    nav + 4,
                    named(
                            Material.DIAMOND,
                            ui.msg()
                                    .get(
                                            "shop.pass.buy_premium_plus",
                                            Placeholder.unparsed("price", String.valueOf(price)),
                                            Placeholder.unparsed(
                                                    "levels",
                                                    String.valueOf(def.premiumPlusLevels())))),
                    e ->
                            ui.confirm(
                                    player,
                                    ui.plain("shop.pass.premium_plus"),
                                    price,
                                    () ->
                                            ui.report(
                                                    player,
                                                    passes.buyPremium(
                                                            player,
                                                            PassMath.PREMIUM_PLUS,
                                                            order))));
        }
        if (level < def.levels()) {
            UUID order = UuidV7.next();
            menu.button(
                    nav + 6,
                    named(
                            Material.EMERALD,
                            ui.msg()
                                    .get(
                                            "shop.pass.buy_level",
                                            Placeholder.unparsed(
                                                    "price",
                                                    String.valueOf(def.prices().level())))),
                    e ->
                            ui.confirm(
                                    player,
                                    ui.plain("shop.pass.level"),
                                    def.prices().level(),
                                    () -> ui.report(player, passes.buyLevels(player, 1, order))));
        }
    }

    private ItemStack levelIcon(PassDef def, int level, int reached, ShopProfile.Pass pass) {
        List<Component> lore = new ArrayList<>();
        for (PassDef.Reward r : def.rewardsAt(level)) {
            boolean claimed = pass.claimed().contains(PassMath.claimKey(r));
            HarborId cosmetic = r.cosmetic();
            String title = r.title();
            String what =
                    cosmetic != null
                            ? rt.cosmetic(cosmetic).map(CosmeticDef::name).orElse(cosmetic.path())
                            : title != null
                                    ? PlainTextComponentSerializer.plainText()
                                            .serialize(
                                                    SeasonServices.titles()
                                                            .display(TitleId.of(title)))
                                    : ui.plain("shop.pass.pearls") + " " + r.pearls();
            lore.add(
                    ui.msg()
                            .get(
                                    "shop.pass.reward_line",
                                    Placeholder.unparsed(
                                            "track",
                                            ui.plain("shop.pass.track." + r.track().key())),
                                    Placeholder.unparsed("reward", what),
                                    Placeholder.unparsed("state", claimed ? "✔" : "")));
        }
        Material material =
                level <= reached
                        ? Material.LIME_STAINED_GLASS_PANE
                        : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack stack =
                named(
                        material,
                        ui.msg()
                                .get(
                                        "shop.pass.level_name",
                                        Placeholder.unparsed("level", String.valueOf(level))));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(lore.stream().map(MenuSupport::noItalic).toList()));
        return stack;
    }
}
