package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.data.CollectionData;
import wiki.creeper.harbor.collection.service.AquariumManager.Candidate;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Confirmation flows before an aquarium donation: one fish from an inventory slot, and the donation
 * box GUI with the items waiting for confirmation. The donation itself is the {@link
 * AquariumManager#donateItems} transaction; the box events are received by {@link AquariumManager}
 * and forwarded here.
 */
final class AquariumDonationFlow {

    private static final int BOX_ROWS = 4;
    private static final long CONFIRM_TIMEOUT_TICKS = 20L * 60;

    private final AquariumManager manager;
    private final HarborApi api;
    private final Tasks tasks;
    private final ItemReturns returns;
    private final Map<UUID, List<ItemStack>> pendingBox = new HashMap<>();

    /**
     * Donation box GUI (put items in and close it to get a confirmation screen). Unlike Core Menu
     * it does not block item movement.
     */
    static final class DonationBox implements InventoryHolder {
        private final Inventory inventory;

        DonationBox(Component title) {
            this.inventory = Bukkit.createInventory(this, BOX_ROWS * 9, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    AquariumDonationFlow(AquariumManager manager, HarborApi api, Tasks tasks, ItemReturns returns) {
        this.manager = manager;
        this.api = api;
        this.tasks = tasks;
        this.returns = returns;
    }

    /**
     * Donates one fish from an inventory slot (after a confirmation screen; shared by the held item
     * and the aquarium progress screen). Cancels if the slot's item changed during confirmation;
     * the donation uses the same {@link AquariumManager#donateItems} transaction.
     *
     * @param changedKey message key for when the slot changed
     */
    CompletableFuture<Boolean> donateSlot(
            Player player, int slot, String source, String changedKey) {
        ItemStack held = player.getInventory().getItem(slot);
        Optional<HarborItem.Fish> item = manager.fishOf(held);
        Optional<FishDef> fish =
                item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
        if (item.isEmpty() || fish.isEmpty() || fish.get().releaseOnly()) {
            api.messages().send(player, "collection.aquarium.not_fish");
            return CompletableFuture.completedFuture(false);
        }
        HarborPlayer hp = api.players().get(player);
        CollectionData data = hp.get(CollectionManager.COMPONENT);
        AquariumRules rules = manager.rules();
        boolean counted =
                data.donations().getOrDefault(fish.get().id(), 0) < rules.perPlayerSpeciesCap();
        boolean first = manager.firstSpecies(fish.get().id());
        int estimate =
                counted
                        ? rules.pointsFor(fish.get().grade(), item.get().quality().stars(), first)
                        : 0;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "collection.aquarium.confirm_body",
                                Placeholder.unparsed("name", fish.get().name()),
                                Placeholder.unparsed("points", String.valueOf(estimate))));
        if (first) {
            body.add(api.messages().get("collection.aquarium.first_species"));
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.aquarium.confirm_title"),
                        body,
                        api.messages().get("collection.aquarium.yes"),
                        api.messages().get("collection.aquarium.no"),
                        ok -> {
                            if (!ok) {
                                result.complete(false);
                                return;
                            }
                            // Cancel if the slot's item changed while the confirmation screen was
                            // open
                            ItemStack now = player.getInventory().getItem(slot);
                            if (now == null
                                    || !manager.fishOf(now).map(item.get()::equals).orElse(false)) {
                                api.messages().send(player, changedKey);
                                result.complete(false);
                                return;
                            }
                            ItemStack taken = now.asOne();
                            now.setAmount(now.getAmount() - 1);
                            var _ =
                                    manager.donateItems(player, List.of(taken), source)
                                            .thenAccept(r -> result.complete(r.ok()));
                        });
        return result;
    }

    /** Shutdown: gives back the items in open donation boxes and those awaiting confirmation. */
    void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false)
                    instanceof DonationBox box) {
                List<ItemStack> items = takeAll(box.getInventory());
                p.closeInventory();
                giveOrMail(p.getUniqueId(), p, items);
            }
        }
        pendingBox.forEach((uuid, items) -> giveOrMail(uuid, Bukkit.getPlayer(uuid), items));
        pendingBox.clear();
    }

    /** Whether the player's items are outside the inventory (open box or pending confirmation). */
    boolean holding(Player player) {
        return pendingBox.containsKey(player.getUniqueId())
                || player.getOpenInventory().getTopInventory().getHolder(false)
                        instanceof DonationBox;
    }

    /**
     * For auto-save (Core ItemCustody): items put in an open donation box and items awaiting the
     * donation confirmation.
     */
    List<ItemStack> held(Player player) {
        List<ItemStack> out = new ArrayList<>();
        if (player.getOpenInventory().getTopInventory().getHolder(false)
                instanceof DonationBox box) {
            for (ItemStack stack : box.getInventory().getContents()) {
                if (stack != null && !stack.isEmpty()) {
                    out.add(stack.clone());
                }
            }
        }
        List<ItemStack> pending = pendingBox.get(player.getUniqueId());
        if (pending != null) {
            pending.forEach(stack -> out.add(stack.clone()));
        }
        return out;
    }

    void open(Player player) {
        if (pendingBox.containsKey(player.getUniqueId())) {
            api.messages().send(player, "collection.aquarium.box_pending");
            return;
        }
        player.openInventory(
                new DonationBox(api.messages().get("collection.aquarium.box_title"))
                        .getInventory());
    }

    void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof DonationBox box)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<ItemStack> items = takeAll(box.getInventory());
        if (items.isEmpty()) {
            return;
        }
        // Next tick: don't open another screen inside the close event
        var _ = tasks.later(1, () -> confirmBox(player, items));
    }

    private void confirmBox(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            returns.giveBack(player.getUniqueId(), items, AquariumManager.RETURN_SOURCE);
            return;
        }
        List<ItemStack> fish = new ArrayList<>();
        List<ItemStack> other = new ArrayList<>();
        for (ItemStack stack : items) {
            Optional<FishDef> def =
                    manager.fishOf(stack)
                            .flatMap(
                                    i ->
                                            api.content()
                                                    .snapshot()
                                                    .get(FishDef.TYPE, i.species().id()));
            if (def.isPresent() && !def.get().releaseOnly()) {
                fish.add(stack);
            } else {
                other.add(stack);
            }
        }
        if (!other.isEmpty()) {
            returns.giveBack(player.getUniqueId(), other, AquariumManager.RETURN_SOURCE);
            api.messages().send(player, "collection.aquarium.box_returned");
        }
        if (fish.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingBox.put(uuid, fish);
        List<Candidate> preview = manager.candidates(player, fish, new ArrayList<>());
        AquariumRules rules = manager.rules();
        List<Component> body = new ArrayList<>();
        int total = 0;
        Set<FishId> firstShown = new HashSet<>();
        Map<String, int[]> lines = new LinkedHashMap<>();
        for (Candidate c : preview) {
            boolean first = manager.firstSpecies(c.fish().id()) && firstShown.add(c.fish().id());
            int points =
                    c.counted()
                            ? rules.pointsFor(c.fish().grade(), c.item().quality().stars(), first)
                            : 0;
            total += points;
            String key = c.fish().name() + "|" + c.item().quality().display() + "|" + first;
            int[] acc = lines.computeIfAbsent(key, k -> new int[2]);
            acc[0]++;
            acc[1] += points;
        }
        lines.forEach(
                (key, acc) -> {
                    String[] parts = key.split("\\|", 3);
                    body.add(
                            api.messages()
                                    .get(
                                            parts[2].equals("true")
                                                    ? "collection.aquarium.box_line_first"
                                                    : "collection.aquarium.box_line",
                                            Placeholder.unparsed("name", parts[0]),
                                            Placeholder.unparsed("quality", parts[1]),
                                            Placeholder.unparsed("count", String.valueOf(acc[0])),
                                            Placeholder.unparsed(
                                                    "points", String.valueOf(acc[1]))));
                });
        body.add(
                api.messages()
                        .get(
                                "collection.aquarium.box_total",
                                Placeholder.unparsed("count", String.valueOf(preview.size())),
                                Placeholder.unparsed("points", String.valueOf(total))));
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.aquarium.confirm_title"),
                        body,
                        api.messages().get("collection.aquarium.yes"),
                        api.messages().get("collection.aquarium.no"),
                        ok -> {
                            List<ItemStack> held = pendingBox.remove(uuid);
                            if (held == null) {
                                return;
                            }
                            if (ok) {
                                var _ = manager.donateItems(player, held, "box");
                            } else {
                                returns.giveBack(uuid, held, AquariumManager.RETURN_SOURCE);
                            }
                        });
        // Closing with ESC sends no result, so give the items back after a timeout
        var _ =
                tasks.later(
                        CONFIRM_TIMEOUT_TICKS,
                        () -> {
                            List<ItemStack> held = pendingBox.remove(uuid);
                            if (held != null) {
                                returns.giveBack(uuid, held, AquariumManager.RETURN_SOURCE);
                            }
                        });
    }

    void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        List<ItemStack> held = pendingBox.remove(uuid);
        if (held != null) {
            // Still inside the quit event, so the inventory has not been saved yet
            giveOrMail(uuid, event.getPlayer(), held);
        }
    }

    /**
     * Puts items in the inventory and mails the overflow (previously the overflow was lost). All
     * mailed when not online.
     */
    private void giveOrMail(UUID uuid, @Nullable Player player, List<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        if (player != null) {
            left.addAll(player.getInventory().addItem(items.toArray(ItemStack[]::new)).values());
        } else {
            left.addAll(items);
        }
        if (!left.isEmpty()) {
            returns.giveBack(
                    uuid, left, AquariumManager.RETURN_SOURCE); // mailed when there is no room
        }
    }

    private static List<ItemStack> takeAll(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty()) {
                items.add(stack.clone());
            }
        }
        inventory.clear();
        return items;
    }
}
