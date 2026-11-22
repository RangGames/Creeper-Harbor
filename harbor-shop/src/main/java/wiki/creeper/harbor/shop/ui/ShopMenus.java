package wiki.creeper.harbor.shop.ui;

import static wiki.creeper.harbor.shop.ui.MenuSupport.named;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.season.api.OwnedTitle;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.BundleDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.order.ShopOrders;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.util.UuidV7;

/**
 * Shop screens (/상점, /보관함, /패스, /보물함). Lists are shown in a chest GUI, and purchases go through a
 * Dialog confirmation (price, balance, no-refund notice). Each purchase button uses the order_id
 * (UUID v7) created when the screen was drawn as its reference ID, so repeated clicks are processed
 * only once (docs 20 12.4). Only server-side results are trusted. Main thread only.
 */
public final class ShopMenus {

    /** Shop tabs. */
    public enum Tab {
        ROTATION,
        PERMANENT,
        SEASON,
        BUNDLE
    }

    private final ShopRuntime rt;
    private final ShopOrders orders;
    private final CosmeticManager cosmetics;
    private final MenuSupport ui;
    private final PassMenu passMenu;
    private final ChestMenu chestMenu;

    public ShopMenus(
            ShopRuntime rt,
            ShopOrders orders,
            CosmeticManager cosmetics,
            PassManager passes,
            ChestManager chests) {
        this.rt = rt;
        this.orders = orders;
        this.cosmetics = cosmetics;
        this.ui = new MenuSupport(rt);
        this.passMenu = new PassMenu(rt, passes, ui);
        this.chestMenu = new ChestMenu(rt, chests, ui);
    }

    private MessageService msg() {
        return ui.msg();
    }

    // ------------------------------------------------------------------ shop

    public void openShop(Player player, Tab tab) {
        List<Object> items = new ArrayList<>();
        int season = rt.season();
        if (tab == Tab.BUNDLE) {
            rt.snapshot().all(BundleDef.TYPE).values().stream()
                    .filter(orders::bundleOnSale)
                    .forEach(items::add);
        } else {
            List<HarborId> rotation = orders.rotation();
            rt.snapshot().all(CosmeticDef.TYPE).values().stream()
                    .filter(c -> c.sellableIn(season))
                    .filter(
                            c ->
                                    switch (tab) {
                                        case ROTATION ->
                                                c.availability()
                                                                == CosmeticDef.Availability.ROTATION
                                                        && rotation.contains(c.id());
                                        case PERMANENT ->
                                                c.availability()
                                                        == CosmeticDef.Availability.PERMANENT;
                                        case SEASON ->
                                                c.availability() == CosmeticDef.Availability.SEASON;
                                        case BUNDLE -> false;
                                    })
                    .sorted(Comparator.comparing(CosmeticDef::category).thenComparing(c -> c.id()))
                    .forEach(items::add);
        }
        PearlService.Balance wallet = rt.profile(player.getUniqueId()).wallet();
        PagedMenu<Object> menu =
                new PagedMenu<>(
                        msg().get(
                                        "shop.menu.title",
                                        Placeholder.unparsed("tab", tabName(tab)),
                                        Placeholder.unparsed(
                                                "pearls", String.valueOf(wallet.total()))),
                        6,
                        items,
                        o -> entry(player, o),
                        named(Material.ARROW, msg().get("shop.menu.previous")),
                        named(Material.ARROW, msg().get("shop.menu.next")));
        menu.show(player, 0);
        int nav = menu.size() - 9;
        int slot = 2;
        for (Tab t : Tab.values()) {
            Material icon =
                    t == tab ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            menu.button(
                    nav + slot++,
                    named(icon, Component.text(tabName(t))),
                    e -> openShop(player, t));
        }
        menu.button(
                nav + 7,
                named(
                        Material.HEART_OF_THE_SEA,
                        msg().get(
                                        "shop.menu.wallet",
                                        Placeholder.unparsed(
                                                "pearls", String.valueOf(wallet.total())),
                                        Placeholder.unparsed(
                                                "paid", String.valueOf(wallet.paid())))),
                null);
    }

    private String tabName(Tab tab) {
        return ui.plain("shop.menu.tab." + tab.name().toLowerCase(Locale.ROOT));
    }

    private PagedMenu.Entry entry(Player player, Object item) {
        UUID orderId = UuidV7.next();
        if (item instanceof BundleDef bundle) {
            ItemStack icon =
                    MenuSupport.icon(
                            bundle.icon(),
                            null,
                            msg().get(
                                            "shop.menu.bundle_name",
                                            Placeholder.unparsed("name", bundle.name())),
                            ui.lines(
                                    bundle.lore(),
                                    msg().get(
                                                    "shop.menu.price",
                                                    Placeholder.unparsed(
                                                            "price",
                                                            String.valueOf(bundle.price()))),
                                    bundle.accountLimit() > 0
                                            ? msg().get(
                                                            "shop.menu.account_limit",
                                                            Placeholder.unparsed(
                                                                    "limit",
                                                                    String.valueOf(
                                                                            bundle.accountLimit())))
                                            : null));
            return new PagedMenu.Entry(
                    icon,
                    e ->
                            ui.confirm(
                                    player,
                                    bundle.name(),
                                    bundle.price(),
                                    () ->
                                            ui.report(
                                                    player,
                                                    orders.buyBundle(player, bundle, orderId))));
        }
        CosmeticDef def = (CosmeticDef) item;
        boolean owned = cosmetics.owns(player.getUniqueId(), def.id());
        boolean requirement = orders.meetsRequirement(player.getUniqueId(), def);
        ItemStack icon =
                ui.cosmeticIcon(
                        def,
                        owned
                                ? msg().get("shop.menu.owned")
                                : msg().get(
                                                "shop.menu.price",
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(def.price()))),
                        requirement ? null : msg().get("shop.menu.requirement"),
                        def.giftable() ? msg().get("shop.menu.gift_hint") : null);
        return new PagedMenu.Entry(
                icon,
                e -> {
                    if (owned) {
                        msg().send(player, "shop.error.owned");
                        return;
                    }
                    ui.confirm(
                            player,
                            def.name(),
                            def.price(),
                            () -> ui.report(player, orders.buyCosmetic(player, def, orderId)));
                });
    }

    // ------------------------------------------------------------------ wardrobe

    public void openWardrobe(Player player, CosmeticDef.@Nullable Category filter) {
        UUID uuid = player.getUniqueId();
        ShopProfile profile = rt.profile(uuid);
        List<CosmeticDef> owned =
                profile.owned().stream()
                        .map(rt::cosmetic)
                        .flatMap(Optional::stream)
                        .filter(c -> filter == null || c.category() == filter)
                        .sorted(
                                Comparator.comparing(CosmeticDef::category)
                                        .thenComparing(c -> c.id()))
                        .toList();
        PagedMenu<CosmeticDef> menu =
                new PagedMenu<>(
                        msg().get("shop.wardrobe.title"),
                        6,
                        owned,
                        def -> {
                            boolean equipped =
                                    def.id().equals(profile.equipped().get(def.category()));
                            ItemStack icon =
                                    ui.cosmeticIcon(
                                            def,
                                            msg().get(
                                                            equipped
                                                                    ? "shop.wardrobe.equipped"
                                                                    : "shop.wardrobe.click_equip"),
                                            null,
                                            null);
                            return new PagedMenu.Entry(
                                    icon,
                                    e -> {
                                        if (equipped) {
                                            cosmetics.unequip(player, def.category());
                                        } else {
                                            cosmetics.equip(player, def.id());
                                        }
                                        openWardrobe(player, filter);
                                    });
                        },
                        named(Material.ARROW, msg().get("shop.menu.previous")),
                        named(Material.ARROW, msg().get("shop.menu.next")));
        menu.show(player, 0);
        int nav = menu.size() - 9;
        menu.button(
                nav + 4,
                named(Material.NAME_TAG, msg().get("shop.wardrobe.titles")),
                e -> openTitles(player));
    }

    /** Title slot (same equipped value as the Season TitleService, docs 20 11.5). */
    private void openTitles(Player player) {
        TitleService titles = SeasonServices.titles();
        var _ =
                titles.list(player.getUniqueId())
                        .thenAccept(
                                list -> {
                                    PagedMenu<OwnedTitle> menu =
                                            new PagedMenu<>(
                                                    msg().get("shop.wardrobe.titles"),
                                                    6,
                                                    list,
                                                    t ->
                                                            new PagedMenu.Entry(
                                                                    named(
                                                                            Material.NAME_TAG,
                                                                            titles.display(
                                                                                    t.title())),
                                                                    e -> {
                                                                        titles.equip(
                                                                                player
                                                                                        .getUniqueId(),
                                                                                t.title());
                                                                        msg().send(
                                                                                        player,
                                                                                        "shop.wardrobe.title_equipped");
                                                                        player.closeInventory();
                                                                    }),
                                                    named(
                                                            Material.ARROW,
                                                            msg().get("shop.menu.previous")),
                                                    named(
                                                            Material.ARROW,
                                                            msg().get("shop.menu.next")));
                                    menu.show(player, 0);
                                    menu.button(
                                            menu.size() - 5,
                                            named(
                                                    Material.BARRIER,
                                                    msg().get("shop.wardrobe.title_clear")),
                                            e -> {
                                                titles.equip(player.getUniqueId(), null);
                                                player.closeInventory();
                                            });
                                });
    }

    // ------------------------------------------------------------------ season pass

    public void openPass(Player player, int page) {
        passMenu.open(player, page);
    }

    // ------------------------------------------------------------------ pearl chest

    public void openChest(Player player) {
        chestMenu.open(player);
    }
}
