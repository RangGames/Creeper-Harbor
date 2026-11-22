package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 판매 상자 (/판매, 추가 제안). 위 5줄에 물고기를 넣으면 마지막 줄 판매 버튼에 합계 견적이 바로 보인다 (일괄 판매 미끄러짐 포함). 판매는 /판매와 같은
 * 트랜잭션(FISH_SELL + 고유 ID 장부 SOLD, 실패하면 반환)을 탄다.
 *
 * <ul>
 *   <li>모두 넣기: 인벤토리 물고기 중 귀한 물고기(잠금·고유 번호·★5)는 빼고 넣는다
 *   <li>귀한 물고기를 직접 넣었으면 판매 버튼을 두 번 눌러야 판다 (첫 클릭은 확인 문구)
 *   <li>Shift+우클릭: 그 물고기 잠금 켜기/끄기 (상자·인벤토리 모두)
 *   <li>어종이 아닌 아이템을 넣으면 다음 틱에 인벤토리로 돌려준다
 *   <li>창을 닫으면 남은 물고기는 인벤토리로 (넘치면 우편함). 접속을 끊으며 닫히면 전부 우편함으로 보낸다 (저장된 인벤토리와 겹치지 않게)
 * </ul>
 *
 * 메인 스레드 전용.
 */
public final class SellMenu implements Listener {

    static final int DEPOSIT = 45;
    private static final int ROWS = 6;
    private static final int FILL = 45;
    private static final int INFO = 47;
    private static final int SELL = 49;
    private static final int RETURN_PROTECTED = 51;
    private static final int CLOSE = 53;

    /** 열린 판매 상자. */
    static final class Box implements InventoryHolder {
        final UUID owner;
        final Inventory inventory;
        long shownTotal;
        boolean armed;
        boolean done;

        Box(UUID owner, Component title) {
            this.owner = owner;
            this.inventory = Bukkit.createInventory(this, ROWS * 9, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final SellService sell;
    private final MailManager mail;
    private final Map<UUID, Box> open = new HashMap<>();

    public SellMenu(HarborApi api, Tasks tasks, SellService sell, MailManager mail) {
        this.api = api;
        this.tasks = tasks;
        this.sell = sell;
        this.mail = mail;
    }

    /** 자동 저장용 (Core ItemCustody): 열린 판매 상자에 넣어 둔 아이템. 판매 처리 중인 것은 이미 상자에서 빠져 있다. */
    public List<ItemStack> held(Player player) {
        Box box = open.get(player.getUniqueId());
        if (box == null || box.done) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>();
        for (int slot = 0; slot < DEPOSIT; slot++) {
            ItemStack stack = box.inventory.getItem(slot);
            if (stack != null && !stack.isEmpty()) {
                out.add(stack.clone());
            }
        }
        return out;
    }

    public void open(Player player) {
        Box previous = open.remove(player.getUniqueId());
        if (previous != null) {
            giveBack(player, previous, false);
        }
        Box box = new Box(player.getUniqueId(), api.messages().get("economy.sell_menu.title"));
        open.put(player.getUniqueId(), box);
        render(player, box);
        player.openInventory(box.inventory);
    }

    // ------------------------------------------------------------------ 그리기

    private void render(Player player, Box box) {
        List<SellService.Line> lines = sell.boxLines(player, box.inventory.getContents(), DEPOSIT);
        long total = lines.stream().mapToLong(SellService.Line::price).sum();
        int fish = SellService.count(lines);
        long guarded = lines.stream().filter(l -> l.protection().protects()).count();
        box.shownTotal = total;
        if (guarded == 0) {
            box.armed = false;
        }
        Inventory inv = box.inventory;
        for (int slot = DEPOSIT; slot < ROWS * 9; slot++) {
            inv.setItem(slot, filler());
        }
        inv.setItem(
                FILL,
                icon(
                        Material.HOPPER,
                        "economy.sell_menu.fill",
                        List.of("economy.sell_menu.fill_hint")));
        inv.setItem(
                INFO,
                icon(
                        Material.BOOK,
                        api.messages().get("economy.sell_menu.info"),
                        List.of(
                                api.messages().get("economy.sell_menu.info_lock"),
                                api.messages().get("economy.sell_menu.info_protect"),
                                api.messages().get("economy.sell_menu.info_close"))));
        List<Component> sellLore = new ArrayList<>();
        sellLore.add(
                api.messages()
                        .get(
                                "economy.sell_menu.sell_lore",
                                Placeholder.unparsed("count", String.valueOf(fish)),
                                Placeholder.unparsed("total", String.valueOf(total))));
        if (guarded > 0) {
            sellLore.add(
                    api.messages()
                            .get(
                                    box.armed
                                            ? "economy.sell_menu.protected_armed"
                                            : "economy.sell_menu.protected_warn",
                                    Placeholder.unparsed("count", String.valueOf(guarded))));
        }
        inv.setItem(
                SELL,
                icon(
                        total > 0
                                ? (box.armed ? Material.GOLD_BLOCK : Material.EMERALD)
                                : Material.GRAY_DYE,
                        api.messages()
                                .get(
                                        "economy.sell_menu.sell",
                                        Placeholder.unparsed("total", String.valueOf(total))),
                        sellLore));
        if (guarded > 0) {
            inv.setItem(
                    RETURN_PROTECTED,
                    icon(Material.SHIELD, "economy.sell_menu.return_protected", List.of()));
        }
        inv.setItem(CLOSE, icon(Material.BARRIER, "economy.sell_menu.close", List.of()));
    }

    private ItemStack icon(Material material, String nameKey, List<String> loreKeys) {
        return icon(
                material,
                api.messages().get(nameKey),
                loreKeys.stream().map(k -> api.messages().get(k)).toList());
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
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

    // ------------------------------------------------------------------ 이벤트

    private @Nullable Box boxOf(Inventory top) {
        return top.getHolder(false) instanceof Box b ? b : null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Box box = boxOf(event.getView().getTopInventory());
        if (box == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true); // 버튼 줄의 아이템을 끌어오지 않게
            return;
        }
        if (raw >= DEPOSIT && raw < ROWS * 9) {
            event.setCancelled(true);
            button(player, box, raw);
            return;
        }
        if (event.getClick() == ClickType.SHIFT_RIGHT) {
            ItemStack current = event.getCurrentItem();
            if (current != null && sell.isFish(current)) {
                event.setCancelled(true);
                boolean locked = ItemLock.toggle(current);
                api.messages()
                        .send(player, locked ? "economy.lock.locked" : "economy.lock.unlocked");
                HarborSounds.play(player, "harbor:ui.click");
            }
        }
        later(player, box);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Box box = boxOf(event.getView().getTopInventory());
        if (box == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= DEPOSIT && raw < ROWS * 9) {
                event.setCancelled(true);
                return;
            }
        }
        later(player, box);
    }

    /** 다음 틱: 어종 아닌 것 돌려주고 견적을 다시 그린다. */
    private void later(Player player, Box box) {
        var _ =
                tasks.later(
                        1,
                        () -> {
                            if (box.done || open.get(player.getUniqueId()) != box) {
                                return;
                            }
                            ejectNonFish(player, box);
                            render(player, box);
                        });
    }

    private void ejectNonFish(Player player, Box box) {
        boolean ejected = false;
        for (int slot = 0; slot < DEPOSIT; slot++) {
            ItemStack stack = box.inventory.getItem(slot);
            if (stack != null && !stack.isEmpty() && !sell.isFish(stack)) {
                box.inventory.setItem(slot, null);
                give(player, List.of(stack));
                ejected = true;
            }
        }
        if (ejected) {
            api.messages().send(player, "economy.sell_menu.not_fish");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Box box = boxOf(event.getInventory());
        if (box == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (open.get(player.getUniqueId()) == box) {
            open.remove(player.getUniqueId());
        }
        giveBack(player, box, event.getReason() == InventoryCloseEvent.Reason.DISCONNECT);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Box box = open.remove(event.getPlayer().getUniqueId());
        if (box != null) {
            giveBack(event.getPlayer(), box, true);
        }
    }

    // ------------------------------------------------------------------ 버튼

    private void button(Player player, Box box, int slot) {
        switch (slot) {
            case FILL -> fill(player, box);
            case SELL -> sell(player, box);
            case RETURN_PROTECTED -> returnProtected(player, box);
            case CLOSE -> player.closeInventory();
            default -> {
                return;
            }
        }
        HarborSounds.play(player, "harbor:ui.click");
    }

    /** 인벤토리 물고기를 넣는다 (귀한 물고기는 그대로 둔다). */
    private void fill(Player player, Box box) {
        PlayerInventory inv = player.getInventory();
        int moved = 0;
        int skipped = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty() || !sell.isFish(stack)) {
                continue;
            }
            if (sell.protectionOf(stack).protects()) {
                skipped++;
                continue;
            }
            Map<Integer, ItemStack> left = addToDeposit(box, stack.clone());
            if (left.isEmpty()) {
                inv.setItem(slot, null);
                moved++;
            } else {
                inv.setItem(slot, left.values().iterator().next());
                break; // 상자가 찼다
            }
        }
        if (skipped > 0) {
            api.messages()
                    .send(
                            player,
                            "economy.sell_menu.skipped_protected",
                            Placeholder.unparsed("count", String.valueOf(skipped)));
        }
        if (moved == 0 && skipped == 0) {
            api.messages().send(player, "economy.sell.nothing");
        }
        render(player, box);
    }

    /** 위 5줄에만 넣는다 (버튼 줄은 건드리지 않음). */
    private static Map<Integer, ItemStack> addToDeposit(Box box, ItemStack stack) {
        int remaining = stack.getAmount();
        for (int slot = 0; slot < DEPOSIT && remaining > 0; slot++) {
            ItemStack in = box.inventory.getItem(slot);
            if (in != null && !in.isEmpty() && in.isSimilar(stack)) {
                int space = in.getMaxStackSize() - in.getAmount();
                int add = Math.min(space, remaining);
                if (add > 0) {
                    in.setAmount(in.getAmount() + add);
                    remaining -= add;
                }
            }
        }
        for (int slot = 0; slot < DEPOSIT && remaining > 0; slot++) {
            ItemStack in = box.inventory.getItem(slot);
            if (in == null || in.isEmpty()) {
                ItemStack put = stack.clone();
                int add = Math.min(stack.getMaxStackSize(), remaining);
                put.setAmount(add);
                box.inventory.setItem(slot, put);
                remaining -= add;
            }
        }
        if (remaining <= 0) {
            return Map.of();
        }
        ItemStack left = stack.clone();
        left.setAmount(remaining);
        return Map.of(0, left);
    }

    private void sell(Player player, Box box) {
        List<SellService.Line> lines = sell.boxLines(player, box.inventory.getContents(), DEPOSIT);
        long total = lines.stream().mapToLong(SellService.Line::price).sum();
        if (lines.isEmpty() || total <= 0) {
            api.messages().send(player, "economy.sell.nothing");
            return;
        }
        if (total != box.shownTotal) {
            api.messages().send(player, "economy.sell.requote");
            render(player, box);
            return;
        }
        boolean guarded = lines.stream().anyMatch(l -> l.protection().protects());
        if (guarded && !box.armed) {
            box.armed = true;
            api.messages().send(player, "economy.sell_menu.confirm_protected");
            render(player, box);
            return;
        }
        for (SellService.Line line : lines) {
            box.inventory.setItem(line.slot(), null);
        }
        if (!sell.sellTaken(player, lines)) {
            // 처리 중: 꺼낸 것을 다시 넣는다
            for (SellService.Line line : lines) {
                box.inventory.setItem(line.slot(), line.stack());
            }
        }
        box.armed = false;
        render(player, box);
    }

    private void returnProtected(Player player, Box box) {
        List<ItemStack> back = new ArrayList<>();
        for (int slot = 0; slot < DEPOSIT; slot++) {
            ItemStack stack = box.inventory.getItem(slot);
            if (stack != null && !stack.isEmpty() && sell.protectionOf(stack).protects()) {
                box.inventory.setItem(slot, null);
                back.add(stack);
            }
        }
        give(player, back);
        render(player, box);
    }

    // ------------------------------------------------------------------ 반환

    /** 남은 물고기를 돌려준다 (한 번만). disconnect면 전부 우편함. */
    private void giveBack(Player player, Box box, boolean disconnect) {
        if (box.done) {
            return;
        }
        box.done = true;
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < DEPOSIT; slot++) {
            ItemStack stack = box.inventory.getItem(slot);
            if (stack != null && !stack.isEmpty()) {
                items.add(stack);
                box.inventory.setItem(slot, null);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        String ref = "sell_box:" + UUID.randomUUID();
        if (disconnect || !player.isOnline()) {
            int i = 0;
            for (ItemStack item : items) {
                var _ = mail.sendItem(player.getUniqueId(), item, "sell_return", ref + ":" + i++);
            }
            return;
        }
        mail.giveOrMail(player.getUniqueId(), items, "sell_return", ref);
    }

    private void give(Player player, List<ItemStack> items) {
        if (!items.isEmpty()) {
            mail.giveOrMail(
                    player.getUniqueId(), items, "sell_return", "sell_box:" + UUID.randomUUID());
        }
    }
}
