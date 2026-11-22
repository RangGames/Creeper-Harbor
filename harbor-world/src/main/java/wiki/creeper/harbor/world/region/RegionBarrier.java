package wiki.creeper.harbor.world.region;

import static wiki.creeper.harbor.world.region.RegionManager.BYPASS;
import static wiki.creeper.harbor.world.region.RegionManager.spawnOf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.UnlockStatus;
import wiki.creeper.harbor.world.api.event.RegionBlockedEvent;

/**
 * Enforces entry conditions: pushes players back out of locked regions, shows the requirement
 * Dialog and blocks teleport/portal bypasses (split out of {@link RegionManager}). Main thread
 * only.
 */
public final class RegionBarrier implements Listener {

    private static final Set<String> CHECKED_CAUSES =
            Set.of("ENDER_PEARL", "COMMAND", "CHORUS_FRUIT", "CONSUMABLE_EFFECT", "SPECTATE");

    private final WorldKit kit;
    private final ServerRouter router;
    private final RegionManager regions;

    RegionBarrier(WorldKit kit, ServerRouter router, RegionManager regions) {
        this.kit = kit;
        this.router = router;
        this.regions = regions;
    }

    private UnlockStatus status(Player player, WorldIndex.Entry entry, boolean ignoreVehicle) {
        return regions.status(player, entry, ignoreVehicle);
    }

    private Optional<WorldIndex.Entry> entryAt(Location location) {
        return regions.entryAt(location);
    }

    private Optional<RegionId> current(UUID player) {
        return regions.current(player);
    }

    private void refresh(Player player, boolean announce) {
        regions.refresh(player, announce);
    }

    @Nullable Location pushBack(
            Player player,
            RegionManager.Track t,
            WorldIndex.Entry region,
            UnlockStatus status,
            Location from,
            Location to,
            @Nullable Vehicle vehicle) {
        Location safe = t.safe;
        Location dest =
                safe != null
                                && Objects.equals(safe.getWorld(), to.getWorld())
                                && safe.distanceSquared(to) < 48 * 48
                        ? safe.clone()
                        : from.clone();
        dest.setYaw(to.getYaw());
        dest.setPitch(to.getPitch());
        Vector push = dest.toVector().subtract(to.toVector()).setY(0);
        if (push.lengthSquared() < 1e-6) {
            double[] edge = region.polygon().nearestEdgePoint(to.getX(), to.getZ());
            push = new Vector(edge[0] - to.getX(), 0, edge[1] - to.getZ());
        }
        Vector velocity =
                push.lengthSquared() < 1e-6
                        ? new Vector()
                        : push.normalize().multiply(kit.balance().pushSpeed());
        Entity mover = vehicle != null ? vehicle : player;
        if (vehicle != null) {
            var _ = vehicle.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        var _ = kit.tasks().later(1, () -> mover.setVelocity(velocity));
        kit.actionBar(
                player,
                "world.region.blocked_bar",
                Placeholder.parsed("name", region.def().name()));
        Bukkit.getPluginManager().callEvent(new RegionBlockedEvent(player, region.info(), status));
        Instant now = Instant.now();
        if (now.isAfter(t.dialogAt.plusSeconds(kit.balance().dialogCooldownSeconds()))) {
            t.dialogAt = now;
            showRequirements(player, region, status);
        }
        return vehicle == null ? dest : null;
    }

    /** Dialog with the unlock requirements and current progress (e.g. "Lv 18 / 20"). */
    void showRequirements(Player player, WorldIndex.Entry region, UnlockStatus status) {
        List<Component> body = new ArrayList<>();
        body.add(
                kit.api()
                        .messages()
                        .get(
                                "world.unlock.header",
                                Placeholder.parsed("name", region.def().name())));
        for (UnlockStatus.Requirement r : status.missing()) {
            body.add(
                    kit.api()
                            .messages()
                            .get(
                                    "world.unlock.req."
                                            + r.kind().name().toLowerCase(java.util.Locale.ROOT),
                                    Placeholder.unparsed("detail", r.detail()),
                                    Placeholder.unparsed("have", String.valueOf(r.have())),
                                    Placeholder.unparsed("need", String.valueOf(r.need()))));
        }
        kit.api().dialogs().notice(player, kit.api().messages().get("world.unlock.title"), body);
    }

    // ------------------------------------------------------------------ bypass blocking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (CHECKED_CAUSES.contains(event.getCause().name()) && !player.hasPermission(BYPASS)) {
            Optional<WorldIndex.Entry> region = entryAt(to);
            if (region.isPresent()
                    && !current(player.getUniqueId()).equals(Optional.of(region.get().id()))) {
                UnlockStatus status = status(player, region.get(), false);
                if (!status.allowed()) {
                    event.setCancelled(true);
                    kit.actionBar(
                            player,
                            "world.region.blocked_bar",
                            Placeholder.parsed("name", region.get().def().name()));
                    Bukkit.getPluginManager()
                            .callEvent(new RegionBlockedEvent(player, region.get().info(), status));
                    return;
                }
            }
        }
        var _ = kit.tasks().later(1, () -> refresh(player, true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        String fromWorld = kit.worldId(event.getFrom());
        String targetWorld =
                switch (event.getCause()) {
                    case NETHER_PORTAL ->
                            fromWorld.equals("harbor_nether") ? "harbor_main" : "harbor_nether";
                    case END_PORTAL ->
                            fromWorld.equals("harbor_end") ? "harbor_main" : "harbor_end";
                    default -> "";
                };
        if (targetWorld.isEmpty()) {
            return;
        }
        Optional<WorldIndex.Entry> target =
                kit.index().regions().stream()
                        .filter(e -> e.def().world().equals(targetWorld))
                        .findFirst();
        if (target.isEmpty()) {
            return;
        }
        WorldIndex.Entry region = target.get();
        if (!player.hasPermission(BYPASS)) {
            UnlockStatus status = status(player, region, false);
            if (!status.allowed()) {
                event.setCancelled(true);
                Bukkit.getPluginManager()
                        .callEvent(new RegionBlockedEvent(player, region.info(), status));
                showRequirements(player, region, status);
                return;
            }
        }
        ServerRouter.Destination dest = spawnOf(region);
        World world = kit.bukkitWorld(dest.world());
        if (world == null) {
            event.setCancelled(true);
            router.send(player, dest, region.ext().server(), "portal");
            return;
        }
        event.setTo(new Location(world, dest.x(), dest.y(), dest.z(), dest.yaw(), 0));
    }
}
