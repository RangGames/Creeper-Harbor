package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.LedgerRecord;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * 내 거래 내역 (/거래내역, 추가 제안). Core CurrencyService.history로 원장 최근 줄을 읽어 보여준다 (새 것부터, 재화별 200줄까지). 사유
 * 이름은 messages_ko.yml {@code economy.history.reason.<사유>}, 없으면 사유 코드 그대로. 읽기만 한다. 메인 스레드 전용.
 */
final class LedgerHistoryMenu {

    private static final int LIMIT = 200;
    private static final int PER_PAGE = 45;

    private final HarborApi api;
    private final Logger log;

    LedgerHistoryMenu(HarborApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    void open(Player player) {
        open(player, Currency.GOLD);
    }

    private void open(Player player, Currency currency) {
        var _ =
                api.currency()
                        .history(player.getUniqueId(), currency, LIMIT)
                        .thenAccept(rows -> show(player, currency, rows, 0))
                        .exceptionally(
                                e -> {
                                    log.warn("거래 내역을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    private void show(Player player, Currency currency, List<LedgerRecord> rows, int page) {
        if (!player.isOnline()) {
            return;
        }
        String cur = currency.name().toLowerCase(Locale.ROOT);
        int pages = Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu =
                new Menu(
                        api.messages()
                                .get(
                                        "economy.history.title",
                                        Placeholder.component(
                                                "currency",
                                                api.messages()
                                                        .get("economy.history.currency." + cur)),
                                        Placeholder.unparsed("page", String.valueOf(shown + 1))),
                        6);
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < rows.size(); i++) {
            menu.button(i, icon(rows.get(start + i), cur), null);
        }
        if (rows.isEmpty()) {
            menu.button(
                    22,
                    named(Material.PAPER, api.messages().get("economy.history.empty"), List.of()),
                    null);
        }
        for (int slot = 45; slot < 54; slot++) {
            menu.button(slot, filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    named(
                            Material.ARROW,
                            api.messages().get("economy.history.previous"),
                            List.of()),
                    e -> show(player, currency, rows, shown - 1));
        }
        Currency other = currency == Currency.GOLD ? Currency.MULBINEUL : Currency.GOLD;
        menu.button(
                49,
                named(
                        other == Currency.GOLD ? Material.GOLD_INGOT : Material.PRISMARINE_CRYSTALS,
                        api.messages()
                                .get(
                                        "economy.history.switch",
                                        Placeholder.component(
                                                "currency",
                                                api.messages()
                                                        .get(
                                                                "economy.history.currency."
                                                                        + other.name()
                                                                                .toLowerCase(
                                                                                        Locale
                                                                                                .ROOT)))),
                        List.of()),
                e -> open(player, other));
        if (shown < pages - 1) {
            menu.button(
                    53,
                    named(Material.ARROW, api.messages().get("economy.history.next"), List.of()),
                    e -> show(player, currency, rows, shown + 1));
        }
        menu.open(player);
    }

    private ItemStack icon(LedgerRecord r, String cur) {
        String reasonKey = "economy.history.reason." + r.reason().toLowerCase(Locale.ROOT);
        Component reason =
                api.messages().has(reasonKey)
                        ? api.messages().get(reasonKey)
                        : Component.text(r.reason());
        TagResolver[] tags = {
            Placeholder.unparsed("amount", String.valueOf(Math.abs(r.delta()))),
            Placeholder.unparsed("balance", String.valueOf(r.balanceAfter())),
            Placeholder.unparsed("time", AuctionHouse.formatTime(api, r.createdAt())),
            Placeholder.component("reason", reason),
            Placeholder.component("unit", api.messages().get("economy.history.unit." + cur))
        };
        return named(
                r.delta() >= 0 ? Material.LIME_DYE : Material.RED_DYE,
                api.messages()
                        .get(
                                r.delta() >= 0 ? "economy.history.gain" : "economy.history.spend",
                                tags),
                List.of(
                        api.messages().get("economy.history.balance", tags),
                        api.messages().get("economy.history.time", tags)));
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
