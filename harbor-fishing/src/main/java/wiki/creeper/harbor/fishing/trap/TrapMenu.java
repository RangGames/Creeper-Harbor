package wiki.creeper.harbor.fishing.trap;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.fishing.api.TrapService;

/**
 * 통발 화면 (docs 20 3.4 '열기'). 위 5줄은 미수확 어획(누르면 그 한 마리 수확), 마지막 줄은 정보·미끼 넣기·모두 수확·슬롯 확장·철거. 아이콘은 실제 어종
 * 아이템이 아니라 모양만 같은 표시용 아이템이다 (수확 트랜잭션이 커밋된 뒤에만 진짜 아이템을 만든다).
 */
final class TrapMenu {

    private static final int CONTENT_SLOTS = 45;

    private final HarborApi api;
    private final TrapManager traps;

    TrapMenu(HarborApi api, TrapManager traps) {
        this.api = api;
        this.traps = traps;
    }

    void open(Player player, TrapRow row) {
        MessageService msg = api.messages();
        String trapName =
                api.content()
                        .snapshot()
                        .get(ItemDef.TYPE, row.item().id())
                        .map(ItemDef::name)
                        .orElse(row.item().toString());
        Menu menu =
                new Menu(
                        msg.get("fishing.trap.menu.title", Placeholder.unparsed("name", trapName)),
                        6);
        List<TrapCatch> contents = row.contents();
        for (int i = 0; i < Math.min(CONTENT_SLOTS, contents.size()); i++) {
            TrapCatch entry = contents.get(i);
            menu.button(i, icon(entry), e -> traps.harvest(player, row, List.of(entry)));
        }
        int slots = row.slots() + row.extraSlots() + traps.bonusSlots(player.getUniqueId());
        Duration cycle =
                TrapMath.cycle(
                        traps.balance().trapCycleHours(),
                        traps.spec(row).cycleMult(),
                        traps.stat(player.getUniqueId(), "trap_cycle_hours"),
                        traps.balance().trapMinCycleHours());
        long minutes =
                Math.max(
                        0, Duration.between(Instant.now(), row.lastCalc().plus(cycle)).toMinutes());
        menu.button(
                45,
                named(
                        Material.PAPER,
                        msg.get("fishing.trap.menu.info"),
                        List.of(
                                msg.get(
                                        "fishing.trap.menu.slots",
                                        Placeholder.unparsed("slots", String.valueOf(slots))),
                                msg.get(
                                        "fishing.trap.menu.bait",
                                        Placeholder.unparsed("bait", String.valueOf(row.bait()))),
                                msg.get(
                                        "fishing.trap.menu.next",
                                        Placeholder.unparsed("minutes", String.valueOf(minutes))),
                                msg.get(
                                        "fishing.trap.menu.stored",
                                        Placeholder.unparsed(
                                                "count", String.valueOf(contents.size()))))),
                null);
        menu.button(
                47,
                named(Material.WHEAT_SEEDS, msg.get("fishing.trap.menu.add_bait"), List.of()),
                e -> traps.addBait(player, row));
        menu.button(
                49,
                named(Material.CHEST, msg.get("fishing.trap.menu.harvest_all"), List.of()),
                e -> {
                    if (contents.isEmpty()) {
                        api.messages().send(player, "fishing.trap.empty");
                    } else {
                        traps.harvest(player, row, contents);
                    }
                });
        long price = traps.slotPrice(row);
        menu.button(
                51,
                named(
                        Material.GOLD_INGOT,
                        msg.get("fishing.trap.menu.expand"),
                        List.of(
                                msg.get(
                                        "fishing.trap.menu.expand_price",
                                        Placeholder.unparsed("price", String.valueOf(price)),
                                        Placeholder.unparsed(
                                                "extra", String.valueOf(row.extraSlots())),
                                        Placeholder.unparsed(
                                                "max",
                                                String.valueOf(
                                                        traps.balance().trapMaxExtraSlots()))))),
                e -> confirmExpand(player, row, price));
        menu.button(
                53,
                named(Material.BARRIER, msg.get("fishing.trap.menu.remove"), List.of()),
                e -> traps.remove(player, row));
        menu.open(player);
    }

    private void confirmExpand(Player player, TrapRow row, long price) {
        MessageService msg = api.messages();
        player.closeInventory();
        api.dialogs()
                .confirm(
                        player,
                        msg.get("fishing.trap.menu.expand"),
                        List.of(
                                msg.get(
                                        "fishing.trap.expand_confirm",
                                        Placeholder.unparsed("price", String.valueOf(price)))),
                        msg.get("fishing.trap.yes"),
                        msg.get("fishing.trap.no"),
                        yes -> {
                            if (!yes) {
                                traps.reopen(player, row.id());
                                return;
                            }
                            var _ =
                                    traps.purchaseSlot(player, row.id())
                                            .thenAccept(
                                                    r -> {
                                                        api.messages()
                                                                .send(
                                                                        player,
                                                                        "fishing.trap.expand."
                                                                                + r.name()
                                                                                        .toLowerCase(
                                                                                                Locale
                                                                                                        .ROOT));
                                                        if (r == TrapService.SlotPurchase.OK) {
                                                            traps.reopen(player, row.id());
                                                        }
                                                    });
                        });
    }

    private ItemStack icon(TrapCatch entry) {
        Optional<FishDef> fish;
        try {
            fish = api.content().snapshot().get(FishDef.TYPE, FishId.parse(entry.fish()).id());
        } catch (IllegalArgumentException e) {
            fish = Optional.empty();
        }
        String baseItem =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        Material material =
                Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        ItemStack stack = ItemStack.of(material);
        MessageService msg = api.messages();
        if (fish.isPresent()) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(fish.get().model()));
            stack.setData(
                    DataComponentTypes.ITEM_NAME,
                    msg.get(
                            "item.name." + fish.get().grade().key(),
                            Placeholder.unparsed("name", fish.get().name())));
        }
        Quality quality = Quality.of(Math.clamp(entry.quality(), Quality.MIN, Quality.MAX));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        List.of(
                                plain(
                                        msg.get(
                                                "fishing.trap.menu.entry",
                                                Placeholder.unparsed(
                                                        "size",
                                                        String.format(
                                                                Locale.ROOT, "%.1f", entry.size())),
                                                Placeholder.unparsed(
                                                        "quality", quality.display()))),
                                plain(msg.get("fishing.trap.menu.click")))));
        return stack;
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            lore.forEach(l -> lines.add(plain(l)));
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        return stack;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
