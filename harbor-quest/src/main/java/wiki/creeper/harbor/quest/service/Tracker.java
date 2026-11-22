package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Matchers;
import wiki.creeper.harbor.quest.logic.QuestMachine;

/**
 * Tracking UI and location checks (docs 20 6.4):
 *
 * <ul>
 *   <li>Progress action bar "Harbor Mackerel 3/10" (batched to once per second per player)
 *   <li>Direction and distance to the current objective of the tracked quest (action bar, every 2
 *       seconds). The vanilla locator bar waypoint will be replaced with a hidden marker entity
 *       after Phase 1 verification
 *   <li>visit objectives: without World region entry events, a regions.yml polygon check once per
 *       10 ticks on moves that change block coordinates, only for players with an active visit
 *       objective
 *   <li>Region entry → chronicle recap (D9)
 * </ul>
 */
public final class Tracker {

    private record Pos(String world, int x, int y, int z) {}

    private final QuestEngine engine;
    private final Map<UUID, Component> pendingBar = new HashMap<>();
    private final Map<UUID, Pos> lastPos = new HashMap<>();
    private final Map<UUID, HarborId> lastRegion = new HashMap<>();

    /**
     * Last direction hint sent and its time (sent again only when it changed or is about to
     * disappear).
     */
    private final Map<UUID, Component> lastWaypoint = new HashMap<>();

    private final Map<UUID, Long> lastWaypointAt = new HashMap<>();

    Tracker(QuestEngine engine) {
        this.engine = engine;
    }

    void forget(UUID uuid) {
        pendingBar.remove(uuid);
        lastPos.remove(uuid);
        lastRegion.remove(uuid);
        lastWaypoint.remove(uuid);
        lastWaypointAt.remove(uuid);
    }

    /** When an objective progresses (action bar batched per second). */
    void progressed(Player player, QuestDef def, Objective o, int value) {
        pendingBar.put(
                player.getUniqueId(),
                engine.api
                        .messages()
                        .get(
                                "quest.actionbar.progress",
                                Placeholder.unparsed("quest", def.name()),
                                Placeholder.unparsed(
                                        "objective", engine.objectiveText(player, def, o)),
                                Placeholder.unparsed("value", String.valueOf(value)),
                                Placeholder.unparsed("count", String.valueOf(o.count()))));
    }

    /** Every 20 ticks: the batched progress action bar. */
    void flushBars() {
        for (Map.Entry<UUID, Component> e : Map.copyOf(pendingBar).entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null && engine.deps.cutscenes().isPlaying(player)) {
                continue; // sent after it ends so it does not cover cutscene lines (action bar)
            }
            pendingBar.remove(e.getKey());
            if (player != null) {
                engine.api.ui().actionBar(player, e.getValue());
            }
        }
    }

    /**
     * Every balance.yml quest.tracker.waypoint_refresh_ticks: direction to the tracked objective.
     * Sent only when the arrow or distance changed or the action bar is about to disappear. Skipped
     * without covering it while fishing, in a cutscene, or while another action bar is showing.
     */
    void showWaypoints() {
        if (!engine.balance().bool("quest.tracker.waypoint_actionbar", true)) {
            return;
        }
        Duration quiet =
                Duration.ofMillis(
                        Math.round(
                                engine.balance().number("quest.tracker.waypoint_quiet_s", 3.0)
                                        * 1000));
        long keepMillis =
                Math.round(engine.balance().number("quest.tracker.waypoint_keep_s", 1.5) * 1000);
        long now = System.currentTimeMillis();
        for (HarborPlayer hp : engine.api.players().online()) {
            UUID uuid = hp.uuid();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null
                    || engine.deps.fishing().isFishing(uuid)
                    || engine.deps.cutscenes().isPlaying(player)) {
                lastWaypoint.remove(uuid);
                continue;
            }
            Optional<Component> bar = waypoint(player, hp);
            if (bar.isEmpty()) {
                lastWaypoint.remove(uuid);
                continue;
            }
            Component c = bar.get();
            boolean changed = !c.equals(lastWaypoint.get(uuid));
            if (!changed && now - lastWaypointAt.getOrDefault(uuid, 0L) < keepMillis) {
                continue;
            }
            if (engine.api.ui().ambientActionBar(player, c, quiet)) {
                lastWaypoint.put(uuid, c);
                lastWaypointAt.put(uuid, now);
            } else {
                lastWaypoint.remove(uuid);
            }
        }
    }

    private Optional<Component> waypoint(Player player, HarborPlayer hp) {
        QuestData data = hp.get(QuestEngine.COMPONENT);
        var tracked = data.tracked();
        if (tracked == null) {
            return Optional.empty();
        }
        QuestEntry e = data.entries().get(tracked);
        Optional<QuestDef> def = engine.def(tracked);
        if (e == null || def.isEmpty() || !e.state().open()) {
            return Optional.empty();
        }
        Location target = null;
        String label;
        if (e.state() == QuestState.READY) {
            Optional<NpcId> npc = def.get().turnInNpc();
            if (npc.isEmpty()) {
                return Optional.empty();
            }
            target = engine.deps.npcs().location(npc.get()).orElse(null);
            label = QuestEngine.plain(engine.deps.npcs().displayName(player, npc.get()));
        } else {
            List<Objective> current = QuestMachine.current(def.get(), e.progress());
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Objective o = current.getFirst();
            label = engine.objectiveText(player, def.get(), o);
            target = location(player, def.get(), o);
        }
        if (target == null
                || target.getWorld() == null
                || !target.getWorld().equals(player.getWorld())) {
            return Optional.empty();
        }
        Location at = java.util.Objects.requireNonNull(player.getLocation());
        double dx = target.getX() - at.getX();
        double dz = target.getZ() - at.getZ();
        double dy = target.getY() - at.getY();
        double distance = Math.sqrt(dx * dx + dz * dz);
        double rel = relativeAngle(dx, dz, at.getYaw());
        double near = engine.balance().number("quest.tracker.waypoint_arrive_m", 3.0);
        double vertical = engine.balance().number("quest.tracker.waypoint_vertical_m", 4.0);
        String arrow = distance <= near ? HERE : arrow(rel);
        Component height =
                Math.abs(dy) < vertical
                        ? Component.empty()
                        : engine.api
                                .messages()
                                .get(
                                        dy > 0
                                                ? "quest.actionbar.waypoint_up"
                                                : "quest.actionbar.waypoint_down",
                                        Placeholder.unparsed(
                                                "height",
                                                String.valueOf(Math.round(Math.abs(dy)))));
        return Optional.of(
                engine.api
                        .messages()
                        .get(
                                "quest.actionbar.waypoint",
                                Placeholder.unparsed("arrow", arrow),
                                Placeholder.component("height", height),
                                Placeholder.unparsed("target", label),
                                Placeholder.unparsed(
                                        "distance", String.valueOf(Math.round(distance)))));
    }

    private static final String HERE = "●";

    /**
     * Angle of the objective relative to the facing direction (-180~180, positive = right).
     * Minecraft yaw is clockwise with 0 = +Z (south), 90 = -X (west).
     */
    static double relativeAngle(double dx, double dz, double yaw) {
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        return ((bearing - yaw) % 360 + 540) % 360 - 180;
    }

    /** -180~180 degrees → 8-direction arrow. */
    static String arrow(double relative) {
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = (int) Math.floorMod(Math.round(relative / 45.0), 8L);
        return arrows[index];
    }

    private @Nullable Location location(Player player, QuestDef def, Objective o) {
        Objective.Waypoint wp = o.waypoint();
        if (wp != null) {
            NpcId npc = wp.npc();
            if (npc != null) {
                return engine.deps.npcs().location(npc).orElse(null);
            }
            String worldId = wp.world();
            World world = worldId == null ? player.getWorld() : world(worldId);
            return world == null ? null : blockCenter(world, wp.x(), wp.y(), wp.z());
        }
        if (o.type().equals("talk") || o.type().equals("deliver")) {
            return QuestEngine.npcOf(def, o)
                    .flatMap(n -> engine.deps.npcs().location(n))
                    .orElse(null);
        }
        var p = o.target().point();
        if (p != null) {
            String worldId = p.world();
            World world = worldId == null ? player.getWorld() : world(worldId);
            return world == null ? null : blockCenter(world, p.x(), p.y(), p.z());
        }
        return null;
    }

    /**
     * Integer coordinates in content are block corners, so point at the block center (decimal
     * coordinates are kept as is).
     */
    private static Location blockCenter(World world, double x, double y, double z) {
        return new Location(world, center(x), y, center(z));
    }

    private static double center(double v) {
        return v == Math.rint(v) ? v + 0.5 : v;
    }

    /** Every 10 ticks: region and point checks only for players whose block coordinates changed. */
    void pollPositions() {
        for (HarborPlayer hp : engine.api.players().online()) {
            Player player = Bukkit.getPlayer(hp.uuid());
            if (player == null) {
                continue;
            }
            Location at = java.util.Objects.requireNonNull(player.getLocation());
            World world = at.getWorld();
            if (world == null) {
                continue;
            }
            String worldId = worldId(world);
            Pos pos = new Pos(worldId, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            if (pos.equals(lastPos.get(hp.uuid()))) {
                continue;
            }
            lastPos.put(hp.uuid(), pos);
            HarborId region = regionAt(worldId, at.getX(), at.getZ());
            HarborId before = lastRegion.get(hp.uuid());
            if (region == null) {
                lastRegion.remove(hp.uuid());
            } else {
                lastRegion.put(hp.uuid(), region);
            }
            engine.chronicle()
                    .onMove(
                            player,
                            hp,
                            region,
                            at.getX(),
                            at.getZ(),
                            region != null && !region.equals(before));
            List<?> visits = engine.index(hp).get("visit");
            if (visits != null && !visits.isEmpty()) {
                engine.progress(
                        player,
                        "visit",
                        o ->
                                Matchers.matchesVisit(
                                        o, region, worldId, at.getX(), at.getY(), at.getZ()),
                        1,
                        false);
            }
        }
    }

    /**
     * Region of a location (regions.yml bounds, balance.yml world_alias, same rules as Fishing).
     */
    @Nullable HarborId regionAt(String worldId, double x, double z) {
        for (RegionDef r : engine.snapshot().all(RegionDef.TYPE).values()) {
            if (r.world().equals(worldId) && r.contains(x, z)) {
                return r.id().id();
            }
        }
        return null;
    }

    String worldId(World world) {
        return engine.balance().string("world_alias." + world.getName()).orElse(world.getName());
    }

    @Nullable World world(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return direct;
        }
        var balance = engine.balance();
        for (String alias : balance.keys("world_alias")) {
            if (balance.string("world_alias." + alias).orElse("").equals(contentWorld)) {
                return Bukkit.getWorld(alias);
            }
        }
        return null;
    }
}
