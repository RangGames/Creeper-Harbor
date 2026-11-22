package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.economy.service.AuctionHouse.Row;

/**
 * Auction house screen (/경매 [category] · /경매 검색 · /경매 내목록). Clicking a slot opens a buy or cancel
 * confirmation screen. Main thread only.
 *
 * <p>Layout (proposed): top row 6 categories · sort · search, middle 4 rows 36 listings, bottom row
 * previous · my auctions · page info (clear search) · trade history · next. Trade history has tabs
 * for what I sold / bought.
 */
final class AuctionMenu {

    /** List state. */
    record View(
            String category, @Nullable String search, boolean mine, int page, AuctionSort sort) {

        View with(int nextPage) {
            return new View(category, search, mine, nextPage, sort);
        }
    }

    static final int PAGE = 36;
    private static final int FIRST = 9;
    private static final int SORT = 7;
    private static final int SEARCH = 8;
    private static final int NAV = 45;

    private final HarborApi api;
    private final Logger log;
    private final AuctionListings listings;
    private final AuctionSettlement settlement;
    private final AuctionReturns returns;

    AuctionMenu(
            HarborApi api,
            Logger log,
            AuctionListings listings,
            AuctionSettlement settlement,
            AuctionReturns returns) {
        this.api = api;
        this.log = log;
        this.listings = listings;
        this.settlement = settlement;
        this.returns = returns;
    }

    /** /경매 [category] · /경매 검색 · /경매 내목록. Main thread. */
    void open(Player player, String category, @Nullable String search, boolean mine, int page) {
        open(player, new View(category, search, mine, page, AuctionSort.LATEST));
    }

    void open(Player player, View view) {
        var _ =
                listings.page(
                                view.category(),
                                view.search(),
                                view.mine() ? player.getUniqueId() : null,
                                view.page(),
                                view.sort(),
                                PAGE)
                        .thenAccept(rows -> show(player, view, rows))
                        .exceptionally(
                                e -> {
                                    log.warn("경매 목록을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    private void show(Player player, View view, List<Row> rows) {
        if (!player.isOnline()) {
            return;
        }
        Component title =
                api.messages()
                        .get(
                                view.mine()
                                        ? "economy.auction.title_mine"
                                        : "economy.auction.title",
                                Placeholder.component(
                                        "category",
                                        api.messages()
                                                .get(
                                                        "economy.auction.category."
                                                                + view.category())),
                                Placeholder.unparsed("page", String.valueOf(view.page() + 1)));
        Menu menu = new Menu(title, 6);
        for (int slot = 0; slot < 9; slot++) {
            menu.button(slot, filler(), null);
        }
        for (int slot = NAV; slot < NAV + 9; slot++) {
            menu.button(slot, filler(), null);
        }
        // Top row: categories · sort · search
        for (int i = 0; i < AuctionHouse.CATEGORIES.size() && i < 6; i++) {
            String cat = AuctionHouse.CATEGORIES.get(i);
            boolean selected = cat.equals(view.category()) && !view.mine();
            menu.button(
                    i,
                    named(
                            selected
                                    ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.WHITE_STAINED_GLASS_PANE,
                            api.messages().get("economy.auction.category." + cat),
                            List.of()),
                    e -> open(player, new View(cat, view.search(), false, 0, view.sort())));
        }
        menu.button(
                SORT,
                named(
                        Material.HOPPER,
                        api.messages()
                                .get(
                                        "economy.auction.sort",
                                        Placeholder.component(
                                                "sort",
                                                api.messages()
                                                        .get(
                                                                "economy.auction.sort_name."
                                                                        + view.sort().key()))),
                        List.of(api.messages().get("economy.auction.sort_hint"))),
                e ->
                        open(
                                player,
                                new View(
                                        view.category(),
                                        view.search(),
                                        view.mine(),
                                        0,
                                        view.sort().next())));
        menu.button(
                SEARCH,
                named(
                        Material.NAME_TAG,
                        api.messages().get("economy.auction.search"),
                        List.of(
                                api.messages()
                                        .get(
                                                view.search() == null
                                                        ? "economy.auction.search_hint"
                                                        : "economy.auction.search_current",
                                                Placeholder.unparsed(
                                                        "search",
                                                        view.search() == null
                                                                ? ""
                                                                : view.search())))),
                e -> askSearch(player, view));
        // Middle: listings
        for (int i = 0; i < rows.size() && i < PAGE; i++) {
            Row row = rows.get(i);
            menu.button(FIRST + i, icon(row, view.mine()), e -> clicked(player, row, view.mine()));
        }
        if (rows.isEmpty()) {
            menu.button(
                    FIRST + 13,
                    named(Material.PAPER, api.messages().get("economy.auction.empty"), List.of()),
                    null);
        }
        // Bottom row
        if (view.page() > 0) {
            menu.button(
                    NAV,
                    named(
                            Material.ARROW,
                            api.messages().get("economy.auction.previous"),
                            List.of()),
                    e -> open(player, view.with(view.page() - 1)));
        }
        menu.button(
                NAV + 2,
                named(Material.CHEST, api.messages().get("economy.auction.my_listings"), List.of()),
                e -> open(player, new View("all", null, true, 0, AuctionSort.LATEST)));
        menu.button(
                NAV + 4,
                named(
                        Material.MAP,
                        api.messages()
                                .get(
                                        "economy.auction.page_info",
                                        Placeholder.unparsed(
                                                "page", String.valueOf(view.page() + 1)),
                                        Placeholder.unparsed("count", String.valueOf(rows.size()))),
                        view.search() == null
                                ? List.of()
                                : List.of(api.messages().get("economy.auction.clear_search"))),
                view.search() == null
                        ? null
                        : e ->
                                open(
                                        player,
                                        new View(
                                                view.category(),
                                                null,
                                                view.mine(),
                                                0,
                                                view.sort())));
        menu.button(
                NAV + 6,
                named(
                        Material.WRITABLE_BOOK,
                        api.messages().get("economy.auction.history"),
                        List.of()),
                e -> history(player, true, view));
        if (rows.size() >= PAGE) {
            menu.button(
                    NAV + 8,
                    named(Material.ARROW, api.messages().get("economy.auction.next"), List.of()),
                    e -> open(player, view.with(view.page() + 1)));
        }
        menu.open(player);
    }

    private void askSearch(Player player, View view) {
        player.closeInventory();
        api.dialogs()
                .input(
                        player,
                        api.messages().get("economy.auction.search"),
                        List.of(api.messages().get("economy.auction.search_body")),
                        api.messages().get("economy.auction.search_label"),
                        view.search() == null ? "" : view.search(),
                        32,
                        false,
                        api.messages().get("economy.auction.yes"),
                        api.messages().get("economy.auction.no"),
                        text ->
                                open(
                                        player,
                                        new View(
                                                view.category(),
                                                text.isBlank() ? null : text,
                                                view.mine(),
                                                0,
                                                view.sort())));
    }

    // ------------------------------------------------------------------ Trade history

    private void history(Player player, boolean sold, View back) {
        var _ =
                listings.history(player.getUniqueId(), sold, PAGE)
                        .thenAccept(rows -> showHistory(player, sold, back, rows))
                        .exceptionally(
                                e -> {
                                    log.warn("경매 기록을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    private void showHistory(
            Player player, boolean sold, View back, List<AuctionListings.HistoryRow> rows) {
        if (!player.isOnline()) {
            return;
        }
        Menu menu =
                new Menu(
                        api.messages()
                                .get(
                                        sold
                                                ? "economy.auction.history_sold"
                                                : "economy.auction.history_bought"),
                        6);
        for (int slot = 0; slot < 9; slot++) {
            menu.button(slot, filler(), null);
        }
        menu.button(
                0,
                named(
                        sold ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                        api.messages().get("economy.auction.history_tab_sold"),
                        List.of()),
                sold ? null : e -> history(player, true, back));
        menu.button(
                1,
                named(
                        sold ? Material.WHITE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                        api.messages().get("economy.auction.history_tab_bought"),
                        List.of()),
                sold ? e -> history(player, false, back) : null);
        menu.button(
                8,
                named(Material.ARROW, api.messages().get("economy.auction.back"), List.of()),
                e -> open(player, back));
        for (int i = 0; i < rows.size() && i < PAGE; i++) {
            AuctionListings.HistoryRow row = rows.get(i);
            TagResolver[] tags = {
                Placeholder.unparsed("name", row.itemName()),
                Placeholder.unparsed("amount", String.valueOf(row.amount())),
                Placeholder.unparsed("price", String.valueOf(row.price())),
                Placeholder.unparsed("fee", String.valueOf(row.fee())),
                Placeholder.unparsed("net", String.valueOf(row.price() - row.fee())),
                Placeholder.unparsed("time", AuctionHouse.formatTime(api, row.soldAt()))
            };
            List<Component> lore = new ArrayList<>();
            lore.add(api.messages().get("economy.auction.history_price", tags));
            if (row.sold()) {
                lore.add(api.messages().get("economy.auction.history_net", tags));
                if (row.held()) {
                    lore.add(api.messages().get("economy.auction.history_held", tags));
                }
            }
            lore.add(api.messages().get("economy.auction.history_time", tags));
            menu.button(
                    FIRST + i,
                    named(
                            row.sold() ? Material.GOLD_INGOT : Material.CHEST,
                            api.messages().get("economy.auction.history_name", tags),
                            lore),
                    null);
        }
        if (rows.isEmpty()) {
            menu.button(
                    FIRST + 13,
                    named(
                            Material.PAPER,
                            api.messages().get("economy.auction.history_empty"),
                            List.of()),
                    null);
        }
        menu.open(player);
    }

    // ------------------------------------------------------------------ Icons

    private ItemStack icon(Row row, boolean mine) {
        ItemStack icon = row.item();
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(icon.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        Duration left = Duration.between(Instant.now(), row.expiresAt());
        TagResolver[] tags = {
            Placeholder.unparsed("price", String.valueOf(row.price())),
            Placeholder.unparsed("unit", String.valueOf(row.price() / Math.max(1, row.amount()))),
            Placeholder.unparsed("seller", row.sellerName()),
            Placeholder.unparsed("hours", String.valueOf(Math.max(0, left.toHours()))),
            Placeholder.unparsed("minutes", String.valueOf(Math.max(0, left.toMinutesPart()))),
            Placeholder.unparsed("ref", String.valueOf(row.refPrice()))
        };
        lore.add(api.messages().get("economy.auction.lore.price", tags));
        if (row.amount() > 1) {
            lore.add(api.messages().get("economy.auction.lore.unit", tags));
        }
        lore.add(api.messages().get("economy.auction.lore.seller", tags));
        lore.add(
                api.messages()
                        .get(
                                left.isNegative()
                                        ? "economy.auction.lore.expired"
                                        : "economy.auction.lore.left",
                                tags));
        lore.add(
                api.messages()
                        .get(
                                mine ? "economy.auction.lore.cancel" : "economy.auction.lore.buy",
                                tags));
        icon.setData(DataComponentTypes.LORE, ItemLore.lore(plain(lore)));
        return icon;
    }

    private static List<Component> plain(List<Component> lines) {
        return lines.stream()
                .map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                .toList();
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) {
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(plain(lore)));
        }
        return stack;
    }

    private static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    private void clicked(Player player, Row row, boolean mine) {
        player.closeInventory();
        TagResolver[] tags = {
            Placeholder.unparsed("name", row.itemName()),
            Placeholder.unparsed("amount", String.valueOf(row.amount())),
            Placeholder.unparsed("price", String.valueOf(row.price())),
            Placeholder.unparsed("seller", row.sellerName())
        };
        if (mine || row.seller().equals(player.getUniqueId())) {
            api.dialogs()
                    .confirm(
                            player,
                            api.messages().get("economy.auction.cancel_title"),
                            List.of(api.messages().get("economy.auction.cancel_body", tags)),
                            api.messages().get("economy.auction.yes"),
                            api.messages().get("economy.auction.no"),
                            ok -> {
                                if (ok) {
                                    returns.cancel(player, row.id());
                                }
                            });
            return;
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.auction.buy_title"),
                        List.of(api.messages().get("economy.auction.buy_body", tags)),
                        api.messages().get("economy.auction.yes"),
                        api.messages().get("economy.auction.no"),
                        ok -> {
                            if (ok) {
                                settlement.buy(player, row);
                            }
                        });
    }
}
