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
 * 추적 UI와 위치 판정 (docs 20 6.4):
 *
 * <ul>
 *   <li>진행 액션바 "항구 고등어 3/10" (플레이어당 1초에 한 번으로 묶음)
 *   <li>추적 중인 퀘스트의 현재 목표 방향·거리 (액션바, 2초마다). 바닐라 위치 표시 막대 waypoint는 Phase 1 검증 뒤 숨김 표식 엔티티로 바꾼다
 *   <li>visit 목표: World 지역 진입 이벤트가 없으면 활성 visit 목표가 있는 플레이어만 블록 좌표가 바뀐 이동에서 10틱에 한 번 regions.yml
 *       다각형 판정
 *   <li>지역 진입 → 연대기 회상 (D9)
 * </ul>
 */
public final class Tracker {

    private record Pos(String world, int x, int y, int z) {}

    private final QuestEngine engine;
    private final Map<UUID, Component> pendingBar = new HashMap<>();
    private final Map<UUID, Pos> lastPos = new HashMap<>();
    private final Map<UUID, HarborId> lastRegion = new HashMap<>();

    /** 마지막으로 보낸 방향 안내와 시각 (바뀌었거나 사라질 때가 됐을 때만 다시 보낸다). */
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

    /** 목표가 진행됐을 때 (액션바는 1초 묶음). */
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

    /** 20틱마다: 묶어 둔 진행 액션바. */
    void flushBars() {
        for (Map.Entry<UUID, Component> e : Map.copyOf(pendingBar).entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null && engine.deps.cutscenes().isPlaying(player)) {
                continue; // 컷씬 대사(액션바)를 덮지 않게 끝난 뒤에 보낸다
            }
            pendingBar.remove(e.getKey());
            if (player != null) {
                engine.api.ui().actionBar(player, e.getValue());
            }
        }
    }

    /**
     * balance.yml quest.tracker.waypoint_refresh_ticks마다: 추적 목표 방향. 화살표·거리가 바뀌었거나 액션바가 사라질 때가 됐을 때만
     * 보낸다. 낚시·컷씬 중이거나 다른 액션바가 떠 있으면 덮지 않고 건너뛴다.
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

    /** 바라보는 방향 기준 목표의 각도 (-180~180, 양수 = 오른쪽). 마인크래프트 yaw는 0 = +Z(남), 90 = -X(서)로 시계 방향이다. */
    static double relativeAngle(double dx, double dz, double yaw) {
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        return ((bearing - yaw) % 360 + 540) % 360 - 180;
    }

    /** -180~180도 → 8방향 화살표. */
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

    /** 콘텐츠의 정수 좌표는 블록 모서리라서 블록 가운데를 가리킨다 (소수 좌표는 그대로). */
    private static Location blockCenter(World world, double x, double y, double z) {
        return new Location(world, center(x), y, center(z));
    }

    private static double center(double v) {
        return v == Math.rint(v) ? v + 0.5 : v;
    }

    /** 10틱마다: 블록 좌표가 바뀐 플레이어만 지역·지점 판정. */
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

    /** 위치의 지역 (regions.yml 경계, balance.yml world_alias, Fishing과 같은 규칙). */
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
