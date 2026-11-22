package wiki.creeper.harbor.core.inventory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Cross-server inventory sync (proposed, built on Harbor session locks with reference to HuskSync's
 * Lockstep behavior).
 *
 * <ul>
 *   <li>Scope: inventory (armor, offhand), ender chest, player PDC, experience, health and food,
 *       potion effects (excluding ambient effects such as beacons), selected hotbar slot
 *   <li>Load: read from the DB before join along with other player data (after taking over the
 *       session lock, so the previous server has finished saving). Applied right on join (before
 *       other modules): crafting grid and cursor are cleared, then overwritten
 *   <li>Save: captured on the main thread via the save hook. On quit, transfer and shutdown, open
 *       windows are closed (cursor and crafting items go to the inventory) before capture, then
 *       written to the DB before handing over the lock (write-behind queue flushNow)
 *   <li>First rollout: for players with no DB row, the existing inventory on the main server
 *       (player file) is the source. Lobby and instances do not sync until a row exists (so an
 *       empty lobby inventory doesn't overwrite the source)
 *   <li>History: recorded to player_inventory_history on quit, transfer, shutdown, death and
 *       restore, keeping only the latest balance.yml inventory.history_keep records
 * </ul>
 *
 * Does nothing if balance.yml inventory.sync is false.
 */
public final class InventorySync implements Listener {

    /**
     * Component value.
     *
     * @param data current state in the DB (null if none = player never synced yet)
     */
    public record Synced(@Nullable InventoryData data) {}

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private final PlayerManager players;
    private final Database db;
    private final WriteBehindQueue queue;
    private final Supplier<ContentSnapshot> content;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;
    private final String server;
    private final ServerRole role;
    private final PlayerComponent<Synced> component;

    /** Players being synced on this server (applied, or the main server's source). */
    private final Set<UUID> synced = new HashSet<>();

    public InventorySync(
            PlayerManager players,
            Database db,
            WriteBehindQueue queue,
            Supplier<ContentSnapshot> content,
            MessageService messages,
            Tasks tasks,
            Logger log,
            String server,
            ServerRole role) {
        this.players = players;
        this.db = db;
        this.queue = queue;
        this.content = content;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
        this.server = server;
        this.role = role;
        this.component =
                new PlayerComponent<>(
                        "inventory",
                        (c, uuid, season) ->
                                new Synced(InventoryRepository.load(c, uuid).orElse(null)),
                        (c, uuid, season, value) -> {
                            InventoryData data = value.data();
                            if (data != null) {
                                InventoryRepository.save(c, uuid, server, data.encode());
                            }
                        });
    }

    /** Registers the component and save hook (before players join). */
    public void register() {
        players.register(component);
        players.addSaveHook(this::beforeSave);
        players.setSaveSoonDelay(
                () -> Math.max(1, balance().integer("inventory.commit_save_delay_ticks", 10)));
    }

    /**
     * Saves the inventory soon after a currency commit. Currency is an immediate transaction while
     * the inventory is write-behind, so if the server dies right after a sale the gold stays but
     * the sold items revert to the last saved state (HuskSync has the same structure and can't
     * prevent this either, proposed). Applies to both grants and deductions.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCurrency(CurrencyChangedEvent event) {
        if (enabled() && synced.contains(event.player())) {
            players.saveSoon(event.player());
        }
    }

    private boolean enabled() {
        return balance().bool("inventory.sync", true);
    }

    private Balance balance() {
        return content.get().single(Balance.TYPE);
    }

    private int keep() {
        return Math.max(1, balance().integer("inventory.history_keep", 20));
    }

    private static String prefix(UUID uuid) {
        return "player:" + uuid + ":";
    }

    // ------------------------------------------------------------------ Apply

    /**
     * Applied before other modules' join handling (season cleanup, reward grants, etc.).
     * PlayerManager attaches data at LOWEST.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        synced.remove(uuid);
        if (!enabled()) {
            return;
        }
        HarborPlayer hp = players.get(uuid).orElse(null);
        if (hp == null) {
            return;
        }
        InventoryData data = hp.get(component).data();
        if (data != null) {
            try {
                InventorySnapshots.apply(player, data);
                synced.add(uuid);
            } catch (RuntimeException e) {
                // Saving without applying would overwrite the source, so don't sync (retry on next
                // join)
                log.error("인벤토리 적용 실패, 이 접속에서는 동기화하지 않습니다: {}", player.getName(), e);
                messages.send(player, "inventory.apply_failed");
            }
        } else if (role == ServerRole.MAIN) {
            // First time: the main server's existing inventory is the source (a row is created on
            // the next save)
            synced.add(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Removed after PlayerManager's (MONITOR) quit save finishes
        var _ =
                tasks.later(
                        1,
                        () -> {
                            if (Bukkit.getPlayer(uuid) == null) {
                                synced.remove(uuid);
                            }
                        });
    }

    // ------------------------------------------------------------------ Save

    private void beforeSave(Player player, HarborPlayer hp, PlayerManager.SaveReason reason) {
        UUID uuid = player.getUniqueId();
        if (!enabled() || !synced.contains(uuid)) {
            return;
        }
        boolean leaving =
                reason == PlayerManager.SaveReason.QUIT
                        || reason == PlayerManager.SaveReason.HANDOFF
                        || reason == PlayerManager.SaveReason.SHUTDOWN;
        if (leaving) {
            // Return items from the cursor, crafting grid and open window to the inventory before
            // capturing
            player.closeInventory();
        }
        // Autosaves and post-trade saves don't close windows, so items outside the inventory
        // (cursor, crafting grid, items held by modules) are put into the snapshot.
        // Otherwise they would be lost if the server died meanwhile. When leaving, windows were
        // already closed above and the items returned to the inventory
        List<ItemStack> outside = leaving ? List.of() : outside(player);
        InventoryData data = InventorySnapshots.capture(player, outside);
        hp.set(component, new Synced(data));
        if (leaving) {
            byte[] encoded = data.encode();
            int keep = keep();
            String why = reason.name();
            // Queued in the same queue so it's written by flushNow before the lock is handed over
            queue.enqueue(
                    prefix(uuid) + "inventory_history",
                    c -> InventoryRepository.addHistory(c, uuid, why, server, encoded, keep));
        }
    }

    /**
     * Main thread: this player's items outside inventory slots (cursor, 2x2 crafting grid, items
     * held by modules).
     */
    private List<ItemStack> outside(Player player) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty()) {
            out.add(cursor.clone());
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            // The result slot (0) is a preview made from the ingredients, so it is excluded
            for (int i = 1; i < Math.min(InventorySnapshots.CRAFTING_SLOTS, top.getSize()); i++) {
                ItemStack item = top.getItem(i);
                if (item != null && !item.isEmpty()) {
                    out.add(item.clone());
                }
            }
        }
        out.addAll(players.custody(player));
        return out;
    }

    /** Records the inventory right before death (for restore; the current state is not changed). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        if (!enabled() || !synced.contains(uuid)) {
            return;
        }
        byte[] encoded = InventorySnapshots.capture(player).encode();
        int keep = keep();
        var _ =
                db.asyncVoid(
                        c ->
                                InventoryRepository.addHistory(
                                        c, uuid, "DEATH", server, encoded, keep));
    }

    // ------------------------------------------------------------------ Admin (InventoryCommand)

    /** Whether this player is being synced on this server. */
    boolean isSynced(UUID uuid) {
        return synced.contains(uuid);
    }

    /** Applies a record to an online player and saves immediately (restore). Main thread. */
    void restoreOnline(Player player, InventoryData data) {
        InventorySnapshots.apply(player, data);
        synced.add(player.getUniqueId());
        players.get(player.getUniqueId()).ifPresent(hp -> hp.set(component, new Synced(data)));
        byte[] encoded = data.encode();
        UUID uuid = player.getUniqueId();
        int keep = keep();
        var _ = players.saveNow(uuid);
        var _ =
                db.asyncVoid(
                        c ->
                                InventoryRepository.addHistory(
                                        c, uuid, "RESTORE", server, encoded, keep));
    }

    /** Replaces an offline player's current state with a record (restore). */
    java.util.concurrent.CompletableFuture<Void> restoreOffline(UUID uuid, InventoryData data) {
        byte[] encoded = data.encode();
        int keep = keep();
        return db.asyncVoid(
                c -> {
                    InventoryRepository.save(c, uuid, server, encoded);
                    InventoryRepository.addHistory(c, uuid, "RESTORE", server, encoded, keep);
                });
    }

    java.util.concurrent.CompletableFuture<List<InventoryRepository.HistoryRow>> history(
            UUID uuid, int limit) {
        return db.async(c -> InventoryRepository.history(c, uuid, limit));
    }

    java.util.concurrent.CompletableFuture<java.util.Optional<InventoryData>> load(
            UUID uuid, @Nullable Long historyId) {
        return db.async(
                c ->
                        historyId == null
                                ? InventoryRepository.load(c, uuid)
                                : InventoryRepository.historyData(c, uuid, historyId));
    }

    static String time(java.time.Instant at) {
        return TIME.format(at);
    }

    // ------------------------------------------------------------------ View window

    /** /harbor inventory view window (read-only). */
    static final class ViewHolder implements InventoryHolder {
        private @Nullable Inventory inventory;

        @Override
        public Inventory getInventory() {
            Inventory inv = inventory;
            if (inv == null) {
                throw new IllegalStateException("창이 아직 없습니다");
            }
            return inv;
        }

        void set(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    /**
     * 54 slots: row 1 = helmet, chestplate, leggings, boots, offhand; rows 2-4 = storage; row 5 =
     * hotbar.
     */
    Inventory view(InventoryData data, String name) {
        ViewHolder holder = new ViewHolder();
        Inventory inv =
                Bukkit.createInventory(
                        holder,
                        54,
                        messages.get("inventory.view_title", Placeholder.unparsed("player", name)));
        holder.set(inv);
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(data.contents());
        int[] armor = {39, 38, 37, 36, 40};
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] < items.length) {
                inv.setItem(i, items[armor[i]]);
            }
        }
        for (int i = 9; i < 36 && i < items.length; i++) {
            inv.setItem(i, items[i]);
        }
        for (int i = 0; i < 9 && i < items.length; i++) {
            inv.setItem(36 + i, items[i]);
        }
        return inv;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onViewClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ViewHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onViewDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ViewHolder) {
            event.setCancelled(true);
        }
    }

    String serverId() {
        return server;
    }
}
