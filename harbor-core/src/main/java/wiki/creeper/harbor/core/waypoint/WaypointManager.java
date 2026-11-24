package wiki.creeper.harbor.core.waypoint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.WaypointService;

/**
 * {@link WaypointService} implementation. Each target is a marker armor stand (no hitbox, no
 * gravity, not saved) with waypoint_transmit_range set, hidden from everyone except its viewer.
 * Color and style go through the vanilla /waypoint modify command from a silent sender (no Paper
 * API). balance.yml {@code waypoint.enabled} turns the feature off and {@code
 * waypoint.invisible_marker} chooses whether the marker is invisible (Phase 1 check: if an
 * invisible entity does not transmit, turn it off and the marker stays hidden from others anyway).
 * Main thread only.
 */
public final class WaypointManager implements WaypointService, Listener {

    /** Moves under this distance are skipped (fewer teleports). */
    private static final double MOVE_EPSILON_SQ = 0.25;

    private record Marker(ArmorStand entity, WaypointService.Target target) {}

    private final Plugin plugin;
    private final Supplier<ContentSnapshot> content;

    /** viewer → owner → target ID → marker. */
    private final Map<UUID, Map<String, Map<String, Marker>>> markers = new HashMap<>();

    private final CommandSender silent = Bukkit.createCommandSender(feedback -> {});

    public WaypointManager(Plugin plugin, Supplier<ContentSnapshot> content) {
        this.plugin = plugin;
        this.content = content;
    }

    @Override
    public void set(Player viewer, String owner, List<Target> targets) {
        Balance balance = content.get().single(Balance.TYPE);
        boolean enabled = balance.bool("waypoint.enabled", true);
        Map<String, Map<String, Marker>> byOwner =
                markers.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        Map<String, Marker> current = byOwner.computeIfAbsent(owner, k -> new HashMap<>());
        Set<String> keep = new HashSet<>();
        if (enabled) {
            boolean invisible = balance.bool("waypoint.invisible_marker", true);
            for (Target target : targets) {
                keep.add(target.id());
                Marker marker = current.get(target.id());
                if (marker != null && !reusable(marker, target)) {
                    marker.entity().remove();
                    marker = null;
                }
                if (marker == null) {
                    ArmorStand entity = spawn(viewer, target, invisible);
                    if (entity != null) {
                        current.put(target.id(), new Marker(entity, target));
                    }
                    continue;
                }
                Location at = marker.entity().getLocation();
                if (at.distanceSquared(target.at()) > MOVE_EPSILON_SQ) {
                    marker.entity().teleport(target.at());
                }
                current.put(target.id(), new Marker(marker.entity(), target));
            }
        }
        current.entrySet()
                .removeIf(
                        e -> {
                            if (keep.contains(e.getKey())) {
                                return false;
                            }
                            e.getValue().entity().remove();
                            return true;
                        });
        if (current.isEmpty()) {
            byOwner.remove(owner);
        }
        if (byOwner.isEmpty()) {
            markers.remove(viewer.getUniqueId());
        }
    }

    /** Same world, style, color and range: the marker can just move. */
    private static boolean reusable(Marker marker, Target target) {
        Target old = marker.target();
        return marker.entity().isValid()
                && Objects.equals(marker.entity().getWorld(), target.at().getWorld())
                && Objects.equals(old.color(), target.color())
                && Objects.equals(old.style(), target.style())
                && old.range() == target.range();
    }

    private @Nullable ArmorStand spawn(Player viewer, Target target, boolean invisible) {
        if (target.at().getWorld() == null) {
            return null;
        }
        ArmorStand stand =
                target.at()
                        .getWorld()
                        .spawn(
                                target.at(),
                                ArmorStand.class,
                                a -> {
                                    a.setPersistent(false);
                                    a.setVisibleByDefault(false);
                                    a.setMarker(true);
                                    a.setInvisible(invisible);
                                    a.setGravity(false);
                                    a.setSilent(true);
                                    a.setInvulnerable(true);
                                    a.setBasePlate(false);
                                    AttributeInstance range =
                                            a.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
                                    if (range == null) {
                                        a.registerAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
                                        range = a.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
                                    }
                                    if (range != null) {
                                        range.setBaseValue(Math.max(1, target.range()));
                                    }
                                });
        viewer.showEntity(plugin, stand);
        String uuid = stand.getUniqueId().toString();
        String color = target.color();
        if (color != null) {
            Bukkit.dispatchCommand(
                    silent, "waypoint modify " + uuid + " color hex " + color.substring(1));
        }
        String style = target.style();
        if (style != null) {
            Bukkit.dispatchCommand(silent, "waypoint modify " + uuid + " style set " + style);
        }
        return stand;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Map<String, Map<String, Marker>> byOwner = markers.remove(event.getPlayer().getUniqueId());
        if (byOwner != null) {
            byOwner.values().forEach(m -> m.values().forEach(x -> x.entity().remove()));
        }
    }

    public void shutdown() {
        markers.values()
                .forEach(
                        o -> o.values().forEach(m -> m.values().forEach(x -> x.entity().remove())));
        markers.clear();
    }
}
