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
 * Sell chest (/판매, proposed). Putting fish in the top 5 rows immediately shows the total quote on
 * the sell button in the last row (bulk-sale slippage included). Selling goes through the same
 * transaction as /판매 (FISH_SELL + unique ID ledger SOLD, returned on failure).
 *
 * <ul>
 *   <li>Add all: adds fish from the inventory except valuable ones (locked, unique number, ★5)
 *   <li>If valuable fish were added by hand, the sell button must be pressed twice (the first click
 *       shows a confirmation message)
 *   <li>Shift+right-click: toggles the lock on that fish (both chest and inventory)
 *   <li>Non-fish items put in are returned to the inventory on the next tick
 *   <li>On close, remaining fish go back to the inventory (mailbox on overflow). If closed by
 *       disconnecting, everything goes to the mailbox (so it does not overlap with the saved
 *       inventory)
 * </ul>
 *
 * Main thread only.
 */
public final class SellMenu implements Listener {

    static final int DEPOSIT = 45;
    private static final int ROWS = 6;
    private static final int FILL = 45;
    private static final int INFO = 47;
    private static final int SELL = 49;
    private static final int RETURN_PROTECTED = 51;
    private static final int CLOSE = 53;

    /** An open sell chest. */
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

    /**
     * For auto-save (Core ItemCustody): items placed in open sell chests. Items being sold have
     * already been removed from the chest.
     */
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

    // ------------------------------------------------------------------ Drawing

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

    // ------------------------------------------------------------------ Events

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
            event.setCancelled(true); // so items in the button row are not pulled in
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

    /** Next tick: return non-fish items and redraw the quote. */
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

    // ------------------------------------------------------------------ Buttons

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

    /** Adds inventory fish (valuable fish are left alone). */
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
                break; // the chest is full
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

    /** Adds only to the top 5 rows (the button row is untouched). */
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
            // In progress: put back what was taken out
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

    // ------------------------------------------------------------------ Returning

    /** Returns remaining fish (only once). On disconnect, everything goes to the mailbox. */
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
