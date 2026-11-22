package wiki.creeper.harbor.world.housing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * Housing decor (docs 20 8.4 'decor shop' · 'personal aquarium'). Buying from the decor shop
 * (housing_decor.yml) updates the ledger (HOUSING_DECOR) and the owned count (housing_decor_owned)
 * in one transaction (ShopService.charge if Economy is present). Bought decor accumulates as a
 * count rather than inventory items and is taken out from the palette inside a plot to place it.
 * Breaking it returns it to the count, and leaving the housing world returns decor items in the
 * inventory to storage (the count).
 *
 * <p>Placed block decor is recorded in the chunk PDC as position → decor ID. Placing tank decor
 * creates a personal aquarium slot (DisplayAnchor, housing_anchor) and notifies Collection via
 * DisplayAnchorChangedEvent. Furniture is ItemDisplay + Interaction (sneak + right-click to recover
 * it).
 */
public final class DecorManager implements Listener {

    private final WorldKit kit;
    private final HousingManager housing;
    private final Map<UUID, Map<HarborId, Integer>> owned = new HashMap<>();
    private final DecorShop shop;
    private final DecorPlacement placement;

    public DecorManager(
            WorldKit kit,
            HousingManager housing,
            Payments payments,
            Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.housing = housing;
        this.shop = new DecorShop(kit, housing, payments, bestTier, this);
        this.placement = new DecorPlacement(kit, housing, this);
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    NamespacedKey tag() {
        return kit.key("decor");
    }

    Optional<DecorDef> def(HarborId id) {
        return kit.api().content().snapshot().get(DecorDef.TYPE, id);
    }

    Optional<DecorDef> def(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String raw = stack.getPersistentDataContainer().get(tag(), PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw).flatMap(this::def);
    }

    public int count(UUID player, HarborId decor) {
        return owned.getOrDefault(player, Map.of()).getOrDefault(decor, 0);
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        var _ =
                kit.api()
                        .database()
                        .async(c -> HousingRepository.decor(c, uuid))
                        .thenAcceptAsync(
                                map -> owned.put(uuid, new HashMap<>(map)), kit.tasks().main());
    }

    void addCount(UUID uuid, HarborId decor, int amount) {
        owned.computeIfAbsent(uuid, u -> new HashMap<>()).merge(decor, amount, Integer::sum);
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.addDecor(c, uuid, decor, amount));
    }

    /** Recovered decor waiting for the world save (owner → decor → count). Main thread only. */
    private final Map<UUID, Map<HarborId, Integer>> unsavedCredits = new HashMap<>();

    private final Set<UUID> worldSavePending = new HashSet<>();

    /**
     * Returns decor removed from the world (blocks, furniture entities) to storage. The removal is
     * saved with the chunk, so writing the count to the DB right away would, if the server died
     * before the chunk was saved, leave the decor both revived in the world and in storage. The
     * in-memory count goes up at once, and the DB is written on the next tick after saving that
     * world (at most one save per tick however many are removed, proposed). The housing world only
     * has chunks near plots loaded, so the save is cheap.
     */
    void creditAfterWorldSave(World world, UUID owner, HarborId decor, int amount) {
        owned.computeIfAbsent(owner, u -> new HashMap<>()).merge(decor, amount, Integer::sum);
        unsavedCredits
                .computeIfAbsent(owner, u -> new HashMap<>())
                .merge(decor, amount, Integer::sum);
        UUID worldId = world.getUID();
        if (!worldSavePending.add(worldId)) {
            return;
        }
        var _ =
                kit.tasks()
                        .later(
                                1,
                                () -> {
                                    worldSavePending.remove(worldId);
                                    World w = Bukkit.getWorld(worldId);
                                    if (w != null) {
                                        w.save();
                                    }
                                    flushCredits(false);
                                });
    }

    /**
     * Writes the pending recovered decor to the DB after the world save (or at shutdown: worlds are
     * saved after plugins are disabled).
     */
    public void flushCredits(boolean blocking) {
        if (unsavedCredits.isEmpty()) {
            return;
        }
        Map<UUID, Map<HarborId, Integer>> batch = new HashMap<>(unsavedCredits);
        unsavedCredits.clear();
        SqlConsumer write =
                c -> {
                    for (var owner : batch.entrySet()) {
                        for (var e : owner.getValue().entrySet()) {
                            HousingRepository.addDecor(c, owner.getKey(), e.getKey(), e.getValue());
                        }
                    }
                };
        if (!blocking) {
            var _ = kit.api().database().asyncVoid(write);
            return;
        }
        // Shutting down: the DB connection closes soon, so write and wait
        try {
            kit.api().database().txVoid(write);
        } catch (java.sql.SQLException e) {
            kit.log().error("장식 회수분 저장 실패 (종료 중): {}", batch, e);
        }
    }

    /** Adjusts the in-memory owned count only (the caller writes the DB). Main thread only. */
    void adjustOwned(UUID uuid, HarborId decor, int delta) {
        owned.computeIfAbsent(uuid, u -> new HashMap<>()).merge(decor, delta, Integer::sum);
    }

    /** In-memory owned counts of a player (decor → count). Main thread only. */
    Map<HarborId, Integer> ownedOf(UUID uuid) {
        return owned.getOrDefault(uuid, Map.of());
    }

    /** Placement/removal listener; register it alongside this manager. */
    public DecorPlacement placement() {
        return placement;
    }

    // ------------------------------------------------------------------ shop

    /** Decor shop (/부두 상점). */
    public void openShop(Player player) {
        shop.openShop(player);
    }

    // ------------------------------------------------------------------ palette

    /**
     * Decor palette (/부두 장식): only inside a plot the player may build in. Click for 1, shift-click
     * for 16.
     */
    public void openPalette(Player player) {
        shop.openPalette(player);
    }

    // ------------------------------------------------------------------ leaving the housing world

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (housing.isHousingWorld(event.getFrom())) {
            stash(event.getPlayer());
        }
    }

    /**
     * Returns decor to storage before the quit save (MONITOR). On a server move the save snapshot
     * was already taken (not saved again on quit), so moving it here would leave it in both the
     * snapshot and storage: moves are handled by {@link #handoffBlocker()} before the save and are
     * skipped here.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (housing.isHousingWorld(player.getWorld())
                && !kit.api().players().frozen(player.getUniqueId())) {
            stash(player);
        }
        owned.remove(player.getUniqueId());
    }

    /**
     * Before a server move (before the save snapshot, main thread): when leaving the housing world,
     * returns decor items to storage. This hook never blocks, so it always allows. Even if another
     * hook denies the move, the decor merely sits in storage (it can be taken out again). Register
     * it last among the World hooks.
     */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            if (housing.isHousingWorld(player.getWorld())) {
                stash(player);
            }
            return HandoffBlocker.ALLOW;
        };
    }

    /** Returns decor items in the inventory to the storage count. */
    private void stash(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean moved = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            Optional<DecorDef> d = def(stack);
            if (d.isPresent() && stack != null) {
                addCount(player.getUniqueId(), d.get().id(), stack.getAmount());
                player.getInventory().setItem(i, null);
                moved = true;
            }
        }
        if (moved) {
            // The storage count is written immediately but the inventory is write-behind, so a
            // crash would
            // leave it in both (on quit the quit save follows right after)
            kit.api().players().saveSoon(player.getUniqueId());
        }
    }
}
