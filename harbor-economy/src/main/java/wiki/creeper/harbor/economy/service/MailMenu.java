package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * Mailbox screen (/우편함). 45 mails per page in the top 5 rows; bottom row previous · claim all ·
 * next. Clicking a mail claims just that one. Main thread only.
 */
final class MailMenu {

    private static final int PER_PAGE = 45;

    private final MailManager mail;

    MailMenu(MailManager mail) {
        this.mail = mail;
    }

    void show(Player player, List<MailManager.Mail> mails) {
        show(player, mails, 0);
    }

    private void show(Player player, List<MailManager.Mail> mails, int page) {
        HarborApi api = mail.api();
        if (!player.isOnline()) {
            return;
        }
        if (mails.isEmpty()) {
            api.messages().send(player, "economy.mail.empty");
            player.closeInventory();
            return;
        }
        int pages = Math.max(1, (mails.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu = new Menu(api.messages().get("economy.mail.title"), 6);
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < mails.size(); i++) {
            MailManager.Mail m = mails.get(start + i);
            menu.button(
                    i,
                    icon(m),
                    e -> {
                        var _ = mail.claim(player, m, true, true);
                    });
        }
        for (int slot = 45; slot < 54; slot++) {
            menu.button(slot, filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    named(Material.ARROW, api.messages().get("economy.mail.previous"), List.of()),
                    e -> show(player, mails, shown - 1));
        }
        long claimable = mails.stream().filter(m -> !m.held()).count();
        menu.button(
                49,
                named(
                        Material.CHEST_MINECART,
                        api.messages()
                                .get(
                                        "economy.mail.claim_all",
                                        Placeholder.unparsed("count", String.valueOf(claimable))),
                        List.of(api.messages().get("economy.mail.claim_all_hint"))),
                e -> {
                    player.closeInventory();
                    mail.claimAll(player);
                });
        if (shown < pages - 1) {
            menu.button(
                    53,
                    named(Material.ARROW, api.messages().get("economy.mail.next"), List.of()),
                    e -> show(player, mails, shown + 1));
        }
        menu.open(player);
    }

    /** Screen icon of one mail. */
    private ItemStack icon(MailManager.Mail mail) {
        HarborApi api = this.mail.api();
        ItemStack item = mail.item();
        ItemStack icon;
        if (item != null) {
            icon = item.clone();
        } else {
            icon = ItemStack.of(Material.GOLD_NUGGET);
            icon.setData(
                    DataComponentTypes.ITEM_NAME,
                    api.messages()
                            .get(
                                    "economy.mail.gold",
                                    Placeholder.unparsed("gold", String.valueOf(mail.gold()))));
        }
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(icon.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        String sourceKey = "economy.mail.source." + mail.source();
        lore.add(
                api.messages()
                        .get(
                                api.messages().has(sourceKey) ? sourceKey : "economy.mail.from",
                                Placeholder.unparsed("source", mail.source())));
        if (mail.held()) {
            Instant until = mail.holdUntil();
            lore.add(
                    until == null
                            ? api.messages().get("economy.mail.held_manual")
                            : api.messages()
                                    .get(
                                            "economy.mail.held_until",
                                            Placeholder.unparsed(
                                                    "time", AuctionHouse.formatTime(api, until))));
        }
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
