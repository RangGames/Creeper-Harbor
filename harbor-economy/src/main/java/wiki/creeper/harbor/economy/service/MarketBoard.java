package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * Market board (/시세, proposed). Lays out every species as an icon and shows its current factor.
 * Opens from economy line "Market Info" rank 1; at rank 2 adds ▲▼ trends versus 24 hours ago and
 * highlights sharply moving species; with "Market Forecast" adds the next-hour forecast (same basis
 * as /시세 &lt;species&gt;). Sort: by number · biggest rise · biggest drop. Main thread only.
 */
final class MarketBoard {

    /** Sort order. */
    enum Order {
        NUMBER,
        RISING,
        FALLING;

        Order next() {
            return switch (this) {
                case NUMBER -> RISING;
                case RISING -> FALLING;
                case FALLING -> NUMBER;
            };
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final StatKey MARKET_INFO = StatKey.of("market_info");
    static final StatKey FORECAST = StatKey.of("feature_market_forecast");
    private static final int PER_PAGE = 45;

    private final HarborApi api;
    private final MarketEngine market;
    private final StationManager stations;

    MarketBoard(HarborApi api, MarketEngine market, StationManager stations) {
        this.api = api;
        this.market = market;
        this.stations = stations;
    }

    /** "Prices are on the fish market board" (names the market_board station if there is one). */
    void sendLocked(org.bukkit.command.CommandSender sender) {
        Component board =
                stations.boardName()
                        .map(Component::text)
                        .map(Component.class::cast)
                        .orElseGet(() -> api.messages().get("economy.market.board.default_name"));
        api.messages().send(sender, "economy.market.locked", Placeholder.component("board", board));
    }

    void open(Player player) {
        double info = api.modifiers().value(player.getUniqueId(), MARKET_INFO);
        if (info < 1) {
            sendLocked(player);
            return;
        }
        if (info < 2) {
            show(player, Map.of(), false, Order.NUMBER, 0);
            return;
        }
        openTrend(player);
    }

    /**
     * Clicked at a fish market price board (station market_board): everyone sees the current
     * prices; economy 'Market Info' rank 2 adds the 24-hour trend as with /시세.
     */
    void openAtBoard(Player player) {
        if (api.modifiers().value(player.getUniqueId(), MARKET_INFO) >= 2) {
            openTrend(player);
        } else {
            show(player, Map.of(), false, Order.NUMBER, 0);
        }
    }

    private void openTrend(Player player) {
        var _ =
                market.coefficientsAt(MarketEngine.currentHour().minus(Duration.ofHours(24)))
                        .thenAccept(
                                before -> {
                                    if (player.isOnline()) {
                                        show(player, before, true, Order.NUMBER, 0);
                                    }
                                });
    }

    private void show(
            Player player, Map<String, Double> before, boolean trend, Order order, int page) {
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        double flat = balance.number("economy.market.board.flat", 0.01);
        int movers = balance.integer("economy.market.board.top_movers", 5);
        boolean forecast = api.modifiers().value(player.getUniqueId(), FORECAST) >= 1;
        List<FishDef> fish = new ArrayList<>(api.content().snapshot().all(FishDef.TYPE).values());
        Map<String, Double> changes = new HashMap<>();
        if (trend) {
            for (FishDef f : fish) {
                String id = f.id().toString();
                changes.put(
                        id,
                        MarketTrend.change(
                                market.coefficient(f.id()), before.getOrDefault(id, Double.NaN)));
            }
        }
        Set<String> top = trend ? MarketTrend.topMovers(changes, movers, flat) : Set.of();
        Comparator<FishDef> byNumber = Comparator.comparingInt(FishDef::no);
        fish.sort(
                switch (order) {
                    case NUMBER -> byNumber;
                    case RISING ->
                            Comparator.comparingDouble(
                                            (FishDef f) ->
                                                    -changes.getOrDefault(f.id().toString(), 0.0))
                                    .thenComparing(byNumber);
                    case FALLING ->
                            Comparator.comparingDouble(
                                            (FishDef f) ->
                                                    changes.getOrDefault(f.id().toString(), 0.0))
                                    .thenComparing(byNumber);
                });
        int pages = Math.max(1, (fish.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu =
                new Menu(
                        api.messages()
                                .get(
                                        "economy.market.board.title",
                                        Placeholder.unparsed("page", String.valueOf(shown + 1)),
                                        Placeholder.unparsed("pages", String.valueOf(pages))),
                        6);
        String baseItem = balance.string("items.fish_base_item").orElse("minecraft:cod");
        Material base = Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < fish.size(); i++) {
            FishDef f = fish.get(start + i);
            menu.button(i, icon(base, f, changes, top, trend, forecast, flat), null);
        }
        for (int slot = 45; slot < 54; slot++) {
            menu.button(slot, filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    named(
                            Material.ARROW,
                            api.messages().get("economy.market.board.previous"),
                            List.of()),
                    e -> show(player, before, trend, order, shown - 1));
        }
        if (trend) {
            menu.button(
                    49,
                    named(
                            Material.HOPPER,
                            api.messages()
                                    .get(
                                            "economy.market.board.order",
                                            Placeholder.component(
                                                    "order",
                                                    api.messages()
                                                            .get(
                                                                    "economy.market.board.order_name."
                                                                            + order.key()))),
                            List.of(api.messages().get("economy.market.board.order_hint"))),
                    e -> show(player, before, trend, order.next(), 0));
        } else {
            menu.button(
                    49,
                    named(
                            Material.BOOK,
                            api.messages().get("economy.market.board.basic"),
                            List.of(api.messages().get("economy.market.board.basic_hint"))),
                    null);
        }
        if (shown < pages - 1) {
            menu.button(
                    53,
                    named(
                            Material.ARROW,
                            api.messages().get("economy.market.board.next"),
                            List.of()),
                    e -> show(player, before, trend, order, shown + 1));
        }
        menu.open(player);
    }

    private ItemStack icon(
            Material base,
            FishDef f,
            Map<String, Double> changes,
            Set<String> top,
            boolean trend,
            boolean forecast,
            double flat) {
        String id = f.id().toString();
        double coef = market.coefficient(f.id());
        List<Component> lore = new ArrayList<>();
        lore.add(
                api.messages()
                        .get(
                                "economy.market.board.coef",
                                Placeholder.unparsed("coef", format(coef))));
        if (trend) {
            double change = changes.getOrDefault(id, 0.0);
            MarketTrend.Direction dir = MarketTrend.direction(change, flat);
            lore.add(
                    api.messages()
                            .get(
                                    "economy.market.board.trend_"
                                            + dir.name().toLowerCase(Locale.ROOT),
                                    Placeholder.unparsed("change", format(Math.abs(change)))));
            if (top.contains(id)) {
                lore.add(api.messages().get("economy.market.board.mover"));
            }
        }
        if (forecast) {
            OptionalDouble next = market.nextCoefficient(f.id());
            if (next.isPresent()) {
                lore.add(
                        api.messages()
                                .get(
                                        "economy.market.board.forecast",
                                        Placeholder.unparsed("coef", format(next.getAsDouble()))));
            }
        }
        ItemStack stack =
                named(
                        base,
                        api.messages()
                                .get(
                                        "economy.market.board.fish",
                                        Placeholder.unparsed("no", String.valueOf(f.no())),
                                        Placeholder.unparsed("name", f.name()),
                                        Placeholder.unparsed("grade", f.grade().key())),
                        lore);
        ItemModels.apply(stack, f.model());
        if (top.contains(id)) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
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
        return stack;
    }

    private static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }
}
