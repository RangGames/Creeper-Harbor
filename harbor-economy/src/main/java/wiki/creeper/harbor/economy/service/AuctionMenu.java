package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
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

/** 경매 목록 화면 (/경매 [분류] · /경매 검색 · /경매 내목록). 칸을 누르면 구매 또는 취소 확인 화면을 띄운다. 메인 스레드 전용. */
final class AuctionMenu {

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

    /** /경매 [분류] · /경매 검색 · /경매 내목록. 메인 스레드. */
    void open(Player player, String category, @Nullable String search, boolean mine, int page) {
        var _ =
                listings.page(category, search, mine ? player.getUniqueId() : null, page)
                        .thenAccept(rows -> show(player, category, search, mine, page, rows))
                        .exceptionally(
                                e -> {
                                    log.warn("경매 목록을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    private void show(
            Player player,
            String category,
            @Nullable String search,
            boolean mine,
            int page,
            List<Row> rows) {
        if (!player.isOnline()) {
            return;
        }
        Component title =
                api.messages()
                        .get(
                                mine ? "economy.auction.title_mine" : "economy.auction.title",
                                Placeholder.component(
                                        "category",
                                        api.messages().get("economy.auction.category." + category)),
                                Placeholder.unparsed("page", String.valueOf(page + 1)));
        Menu menu = new Menu(title, 6);
        for (int i = 0; i < rows.size() && i < AuctionListings.PAGE; i++) {
            Row row = rows.get(i);
            menu.button(i, icon(row, mine), e -> clicked(player, row, mine));
        }
        int nav = 45;
        if (page > 0) {
            menu.button(
                    nav,
                    named(Material.ARROW, "economy.auction.previous"),
                    e -> open(player, category, search, mine, page - 1));
        }
        for (int i = 0; i < AuctionHouse.CATEGORIES.size(); i++) {
            String cat = AuctionHouse.CATEGORIES.get(i);
            ItemStack button =
                    named(
                            cat.equals(category) && !mine
                                    ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.GRAY_STAINED_GLASS_PANE,
                            "economy.auction.category." + cat);
            menu.button(nav + 1 + i, button, e -> open(player, cat, null, false, 0));
        }
        menu.button(
                nav + 7,
                named(Material.CHEST, "economy.auction.my_listings"),
                e -> open(player, "all", null, true, 0));
        if (rows.size() >= AuctionListings.PAGE) {
            menu.button(
                    nav + 8,
                    named(Material.ARROW, "economy.auction.next"),
                    e -> open(player, category, search, mine, page + 1));
        }
        menu.open(player);
    }

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
            Placeholder.unparsed("seller", row.sellerName()),
            Placeholder.unparsed("hours", String.valueOf(Math.max(0, left.toHours()))),
            Placeholder.unparsed("minutes", String.valueOf(Math.max(0, left.toMinutesPart()))),
            Placeholder.unparsed("ref", String.valueOf(row.refPrice()))
        };
        lore.add(api.messages().get("economy.auction.lore.price", tags));
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
        icon.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return icon;
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
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
