package wiki.creeper.harbor.fishing.trap;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.TrapAction;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;

/**
 * Trap placement (buoy spot, count limit, block after row insert) and opening (owner check →
 * offline calculation → GUI). Main thread only.
 */
final class TrapPlacement {

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final FishingManager fishing;
    private final TrapManager traps;
    private final TrapBlocks blocks;
    private final TrapMenu menu;

    TrapPlacement(
            HarborApi api,
            Tasks tasks,
            Logger log,
            FishingManager fishing,
            TrapManager traps,
            TrapBlocks blocks,
            TrapMenu menu) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.fishing = fishing;
        this.traps = traps;
        this.blocks = blocks;
        this.menu = menu;
    }

    /**
     * Places a trap near the buoy spot closest to the target block.
     *
     * @param hand stack one trap item is taken from (returned on failure), or null for a free
     *     placement (API admin placement)
     */
    CompletableFuture<TrapAction> place(
            Player player, @Nullable ItemStack hand, ItemDef def, Block target) {
        FishingBalance b = traps.balance();
        UUID uuid = player.getUniqueId();
        if (hand != null && api.players().frozen(uuid)) {
            // The server move snapshot is already taken: an item taken now would come back
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.FAILED));
        }
        Location targetLoc = target.getLocation().add(0.5, 0, 0.5);
        Optional<RegionDef> region = fishing.regionAt(targetLoc);
        if (region.isEmpty() || region.get().trapZone().isEmpty()) {
            api.messages().send(player, "fishing.trap.not_zone");
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.NOT_ZONE));
        }
        RegionDef.Point buoy = TrapBlocks.nearestBuoy(region.get(), targetLoc, b.trapBuoyRadius());
        if (buoy == null) {
            api.messages().send(player, "fishing.trap.not_zone");
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.NOT_ZONE));
        }
        World world = target.getWorld();
        Block spot =
                world.getBlockAt(
                        (int) Math.floor(buoy.x()), target.getY(), (int) Math.floor(buoy.z()));
        if (!spot.getType().isAir() && spot.getType() != Material.WATER) {
            api.messages().send(player, "fishing.trap.blocked");
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.BLOCKED));
        }
        TrapMath.Spec spec = TrapMath.Spec.of(def.stats());
        if (spec.deep() && traps.stat(uuid, "feature_deep_trap") <= 0) {
            api.messages().send(player, "fishing.trap.deep_locked");
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.LOCKED));
        }
        int max =
                TrapMath.maxTraps(
                        b.trapBaseCount(), traps.stat(uuid, "trap_count"), b.trapMaxCount());
        // Take the item first and give it back on failure (duplication prevention)
        @Nullable ItemStack refund = hand == null ? null : takeOne(hand);
        int season = traps.season();
        RegionId regionId = region.get().id();
        String worldName = world.getName();
        int x = spot.getX();
        int y = spot.getY();
        int z = spot.getZ();
        Instant now = Instant.now();
        return traps.track(
                        uuid,
                        api.database()
                                .async(
                                        c -> {
                                            if (TrapRepository.count(c, uuid, season) >= max) {
                                                return -1L;
                                            }
                                            return TrapRepository.insert(
                                                    c, uuid, season, regionId, worldName, x, y, z,
                                                    spec, def.id(), now);
                                        }))
                .handleAsync(
                        (id, error) -> {
                            if (error != null || id == null || id < 0) {
                                if (refund != null) {
                                    fishing.give(player, refund);
                                }
                                boolean dup = error != null && duplicate(error);
                                api.messages()
                                        .send(
                                                player,
                                                error == null
                                                        ? "fishing.trap.limit"
                                                        : dup
                                                                ? "fishing.trap.occupied"
                                                                : "fishing.trap.failed",
                                                Placeholder.unparsed("max", String.valueOf(max)));
                                if (error != null && !dup) {
                                    log.warn("통발 설치 실패: {}", String.valueOf(error));
                                }
                                return TrapAction.of(
                                        error == null
                                                ? TrapAction.Outcome.LIMIT
                                                : dup
                                                        ? TrapAction.Outcome.OCCUPIED
                                                        : TrapAction.Outcome.FAILED);
                            }
                            blocks.build(spot, id, def);
                            api.messages()
                                    .send(
                                            player,
                                            "fishing.trap.placed",
                                            Placeholder.unparsed("name", def.name()));
                            return TrapAction.of(TrapAction.Outcome.OK, id);
                        },
                        tasks.main());
    }

    private static ItemStack takeOne(ItemStack hand) {
        ItemStack one = hand.asOne();
        hand.setAmount(hand.getAmount() - 1);
        return one;
    }

    private static boolean duplicate(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    void open(Player player, long id, Block block) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(c -> TrapRepository.byId(c, id, false))
                        .whenCompleteAsync(
                                (row, error) -> {
                                    if (error != null) {
                                        api.messages().send(player, "fishing.trap.db_down");
                                        return;
                                    }
                                    if (row.isEmpty()) {
                                        // Clean up leftover blocks of a retrieved trap
                                        blocks.demolish(
                                                block.getWorld().getName(),
                                                block.getX(),
                                                block.getY(),
                                                block.getZ(),
                                                id);
                                        return;
                                    }
                                    TrapRow r = row.get();
                                    if (!r.owner().equals(uuid)) {
                                        OfflinePlayer owner = Bukkit.getOfflinePlayer(r.owner());
                                        String name = owner.getName();
                                        api.ui()
                                                .actionBar(
                                                        player,
                                                        api.messages()
                                                                .get(
                                                                        "fishing.trap.owner",
                                                                        Placeholder.unparsed(
                                                                                "player",
                                                                                name == null
                                                                                        ? "?"
                                                                                        : name)));
                                        return;
                                    }
                                    var _ =
                                            traps.calculate(player, r.id())
                                                    .thenAccept(
                                                            calc -> {
                                                                calc.message(api, player);
                                                                menu.open(player, calc.row());
                                                            });
                                },
                                tasks.main());
    }
}
