package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.gui.Menu;

/** 우편함 화면 (/우편함). 위 5줄에 우편 45개씩, 아랫줄 이전 · 모두 받기 · 다음. 우편을 누르면 그것만 받는다. 메인 스레드 전용. */
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
                    mail.icon(m),
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
