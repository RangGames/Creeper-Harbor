package wiki.creeper.harbor.shop.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.season.api.OwnedTitle;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.BundleDef;
import wiki.creeper.harbor.shop.content.ChestDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.PassDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.order.ShopOrders;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pass.PassMath;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.util.UuidV7;

/**
 * Shop 화면 (/상점, /보관함, /패스, /보물함). 상자 GUI로 목록을 보여 주고, 구매는 Dialog 확인(가격·잔액·환불 불가 안내) 뒤에 한다. 구매 버튼마다
 * 화면을 그릴 때 만든 order_id(UUID v7)를 참조 ID로 쓰므로 연타해도 한 번만 처리된다 (docs 20 12.4). 결과는 서버 계산만 믿는다. 메인 스레드
 * 전용.
 */
public final class ShopMenus {

    /** 상점 탭. */
    public enum Tab {
        ROTATION,
        PERMANENT,
        SEASON,
        BUNDLE
    }

    private final ShopRuntime rt;
    private final ShopOrders orders;
    private final CosmeticManager cosmetics;
    private final PassManager passes;
    private final ChestManager chests;

    public ShopMenus(
            ShopRuntime rt,
            ShopOrders orders,
            CosmeticManager cosmetics,
            PassManager passes,
            ChestManager chests) {
        this.rt = rt;
        this.orders = orders;
        this.cosmetics = cosmetics;
        this.passes = passes;
        this.chests = chests;
    }

    private MessageService msg() {
        return rt.api().messages();
    }

    // ------------------------------------------------------------------ 상점

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
        return plain("shop.menu.tab." + tab.name().toLowerCase(Locale.ROOT));
    }

    private String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(msg().get(key));
    }

    private PagedMenu.Entry entry(Player player, Object item) {
        UUID orderId = UuidV7.next();
        if (item instanceof BundleDef bundle) {
            ItemStack icon =
                    icon(
                            bundle.icon(),
                            null,
                            msg().get(
                                            "shop.menu.bundle_name",
                                            Placeholder.unparsed("name", bundle.name())),
                            lines(
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
                            confirm(
                                    player,
                                    bundle.name(),
                                    bundle.price(),
                                    () ->
                                            report(
                                                    player,
                                                    orders.buyBundle(player, bundle, orderId))));
        }
        CosmeticDef def = (CosmeticDef) item;
        boolean owned = cosmetics.owns(player.getUniqueId(), def.id());
        boolean requirement = orders.meetsRequirement(player.getUniqueId(), def);
        ItemStack icon =
                cosmeticIcon(
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
                    confirm(
                            player,
                            def.name(),
                            def.price(),
                            () -> report(player, orders.buyCosmetic(player, def, orderId)));
                });
    }

    /** 구매 확인 Dialog: 가격, 잔액, 사용 순서, 환불 불가 안내 (전자상거래법 표시 사항, 법률 검토 항목). */
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

    // ------------------------------------------------------------------ 보관함

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
                                    cosmeticIcon(
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

    /** 칭호 슬롯 (Season TitleService와 같은 장착 값, docs 20 11.5). */
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

    // ------------------------------------------------------------------ 시즌 패스

    public void openPass(Player player, int page) {
        Optional<PassDef> found = rt.currentPass();
        if (found.isEmpty()) {
            msg().send(player, "shop.pass.none");
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
                        msg().get(
                                        "shop.pass.title",
                                        Placeholder.unparsed("name", def.name()),
                                        Placeholder.unparsed("level", String.valueOf(level)),
                                        Placeholder.unparsed("max", String.valueOf(def.levels()))),
                        6,
                        levels,
                        l -> new PagedMenu.Entry(levelIcon(def, l, level, pass), e -> {}),
                        named(Material.ARROW, msg().get("shop.menu.previous")),
                        named(Material.ARROW, msg().get("shop.menu.next")));
        menu.show(player, page);
        int nav = menu.size() - 9;
        menu.button(
                nav + 2,
                named(
                        Material.EXPERIENCE_BOTTLE,
                        msg().get(
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
                            msg().get(
                                            "shop.pass.buy_premium",
                                            Placeholder.unparsed(
                                                    "price",
                                                    String.valueOf(def.prices().premium())))),
                    e ->
                            confirm(
                                    player,
                                    plain("shop.pass.premium"),
                                    def.prices().premium(),
                                    () ->
                                            report(
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
                            msg().get(
                                            "shop.pass.buy_premium_plus",
                                            Placeholder.unparsed("price", String.valueOf(price)),
                                            Placeholder.unparsed(
                                                    "levels",
                                                    String.valueOf(def.premiumPlusLevels())))),
                    e ->
                            confirm(
                                    player,
                                    plain("shop.pass.premium_plus"),
                                    price,
                                    () ->
                                            report(
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
                            msg().get(
                                            "shop.pass.buy_level",
                                            Placeholder.unparsed(
                                                    "price",
                                                    String.valueOf(def.prices().level())))),
                    e ->
                            confirm(
                                    player,
                                    plain("shop.pass.level"),
                                    def.prices().level(),
                                    () -> report(player, passes.buyLevels(player, 1, order))));
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
                                    : plain("shop.pass.pearls") + " " + r.pearls();
            lore.add(
                    msg().get(
                                    "shop.pass.reward_line",
                                    Placeholder.unparsed(
                                            "track", plain("shop.pass.track." + r.track().key())),
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
                        msg().get(
                                        "shop.pass.level_name",
                                        Placeholder.unparsed("level", String.valueOf(level))));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(lore.stream().map(ShopMenus::noItalic).toList()));
        return stack;
    }

    // ------------------------------------------------------------------ 보물함

    public void openChest(Player player) {
        if (!chests.enabled()) {
            msg().send(player, "shop.chest.closed");
            return;
        }
        List<ChestDef> list = chests.available();
        if (list.isEmpty()) {
            msg().send(player, "shop.chest.closed");
            return;
        }
        ChestDef chest = list.getFirst();
        var _ =
                chests.pity(player.getUniqueId(), chest)
                        .thenAccept(pity -> showChest(player, chest, pity));
    }

    private void showChest(Player player, ChestDef chest, int pity) {
        Menu menu =
                new Menu(
                        msg().get("shop.chest.title", Placeholder.unparsed("name", chest.name())),
                        6);
        int slot = 0;
        for (ChestDef.Entry e : chest.entries()) {
            if (slot >= 36) {
                break;
            }
            Optional<CosmeticDef> def = rt.cosmetic(e.cosmetic());
            Integer exchange = chest.exchange().get(e.grade());
            UUID order = UuidV7.next();
            ItemStack icon =
                    def.map(
                                    d ->
                                            cosmeticIcon(
                                                    d,
                                                    msg().get(
                                                                    "shop.chest.rate",
                                                                    Placeholder.unparsed(
                                                                            "rate", e.display())),
                                                    exchange == null
                                                            ? null
                                                            : msg().get(
                                                                            "shop.chest.exchange",
                                                                            Placeholder.unparsed(
                                                                                    "shards",
                                                                                    String.valueOf(
                                                                                            exchange))),
                                                    null))
                            .orElse(named(Material.PAPER, Component.text(e.cosmetic().path())));
            menu.button(
                    slot++,
                    icon,
                    exchange == null
                            ? null
                            : ev ->
                                    report(
                                            player,
                                            chests.exchange(player, chest, e.cosmetic(), order)
                                                    .thenApply(ShopMenus::errorKey)));
        }
        menu.button(
                45,
                named(
                        Material.CLOCK,
                        msg().get(
                                        "shop.chest.pity",
                                        Placeholder.unparsed("count", String.valueOf(pity)),
                                        Placeholder.unparsed(
                                                "max", String.valueOf(chest.pity().count())),
                                        Placeholder.unparsed(
                                                "shards",
                                                String.valueOf(
                                                        rt.profile(player.getUniqueId())
                                                                .shards())))),
                null);
        for (int count : List.of(1, 10)) {
            long price = count == 1 ? chest.priceSingle() : chest.priceTen();
            UUID order = UuidV7.next();
            menu.button(
                    count == 1 ? 48 : 50,
                    named(
                            count == 1 ? Material.CHEST : Material.ENDER_CHEST,
                            msg().get(
                                            "shop.chest.open",
                                            Placeholder.unparsed("count", String.valueOf(count)),
                                            Placeholder.unparsed("price", String.valueOf(price)))),
                    e ->
                            confirm(
                                    player,
                                    chest.name() + " ×" + count,
                                    price,
                                    () -> {
                                        var _ =
                                                chests.open(player, chest, count, order)
                                                        .thenAccept(r -> showPulls(player, r));
                                    }));
        }
        menu.open(player);
    }

    private void showPulls(
            Player player, Result<ChestManager.Opened, PearlManager.Failure> result) {
        switch (result) {
            case Result.Err<ChestManager.Opened, PearlManager.Failure>(var f) ->
                    msg().send(player, f.messageKey());
            case Result.Ok<ChestManager.Opened, PearlManager.Failure>(var opened) -> {
                for (ChestManager.Pull p : opened.pulls()) {
                    msg().send(
                                    player,
                                    p.duplicate() ? "shop.chest.pull_duplicate" : "shop.chest.pull",
                                    Placeholder.unparsed("name", chests.name(p.cosmetic())),
                                    Placeholder.unparsed("grade", p.grade().key()),
                                    Placeholder.unparsed("shards", String.valueOf(p.shards())));
                }
                player.closeInventory();
            }
        }
    }

    static Optional<String> errorKey(Result<?, PearlManager.Failure> result) {
        return switch (result) {
            case Result.Ok<?, PearlManager.Failure> ok -> Optional.empty();
            case Result.Err<?, PearlManager.Failure>(PearlManager.Failure f) ->
                    Optional.of(f.messageKey());
        };
    }

    // ------------------------------------------------------------------ 아이콘

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

    private List<Component> lines(List<String> lore, @Nullable Component a, @Nullable Component b) {
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

    private static ItemStack icon(
            String base, @Nullable String model, Component name, List<Component> lore) {
        Material material =
                Optional.ofNullable(Material.matchMaterial(base)).orElse(Material.PAPER);
        ItemStack stack = ItemStack.of(material);
        ItemModels.apply(stack, model);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(lore.stream().map(ShopMenus::noItalic).toList()));
        return stack;
    }

    static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
