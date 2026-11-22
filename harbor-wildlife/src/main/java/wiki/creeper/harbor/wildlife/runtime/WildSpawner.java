package wiki.creeper.harbor.wildlife.runtime;

import io.papermc.paper.world.MoonPhase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.event.WildFishSpawnEvent;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.MediumField;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.SpawnOrder;
import wiki.creeper.harbor.wildlife.sim.SpawnPlanner;
import wiki.creeper.harbor.wildlife.sim.SpawnTable;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;
import wiki.creeper.harbor.wildlife.sim.Steer;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * Wild fish spawning ('17-3' 9.4 natural spawns). Spawns naturally up to the per-player target
 * within the schools-per-second, fish-per-tick and global caps, places one extra school at fish
 * school spots, and submits spawn orders from commands/API to the simulation thread. Main thread
 * only.
 */
final class WildSpawner {

    /** Schools ordered but not yet in a frame (prevents duplicate spawns). */
    private record Recent(String world, Vec3 pos, int count, long seq, @Nullable String tag) {}

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final WildlifeManager manager;
    private final SpawnBoosts boosts;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, Integer> credits = new HashMap<>();
    private final List<Recent> recent = new ArrayList<>();
    private final Map<String, FishingSpot> anchors = new HashMap<>();
    private int spawnCursor;

    WildSpawner(WildlifeKit kit, SimEngine engine, WildlifeManager manager, SpawnBoosts boosts) {
        this.kit = kit;
        this.engine = engine;
        this.manager = manager;
        this.boosts = boosts;
    }

    /** Uses every non-hidden fish school spot as an anchor. */
    void loadAnchors() {
        for (RegionInfo r : kit.regions.regions()) {
            for (FishingSpot s : kit.spots.spots(r.id())) {
                if (!s.hidden()) {
                    anchors.put(s.id(), s);
                }
            }
        }
    }

    // ---------------------------------------------------------------- natural spawns

    void refillCredits(WildlifeBalance bal) {
        credits.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            credits.put(p.getUniqueId(), bal.schoolsPerPlayerPerSecond());
        }
    }

    /** Clears order records older than two frames. */
    void pruneRecent() {
        long seq = manager.frame().seq();
        recent.removeIf(r -> r.seq() < seq - 2);
    }

    void forgetPlayer(UUID id) {
        credits.remove(id);
    }

    private int recentCount() {
        int n = 0;
        for (Recent r : recent) {
            n += r.count();
        }
        return n;
    }

    void spawnPass(WildlifeBalance bal) {
        List<? extends Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int total = manager.frame().fish().size() + recentCount();
        int fishBudget = bal.spawnPerTick();
        ProfileIndex index = kit.index();
        int n = players.size();
        for (int i = 0; i < n && fishBudget > 0 && total < bal.globalMax(); i++) {
            Player p = players.get((spawnCursor + i) % n);
            UUID pid = p.getUniqueId();
            int credit = credits.getOrDefault(pid, 0);
            if (credit <= 0) {
                continue;
            }
            Optional<RegionId> region = kit.regions.current(pid);
            if (region.isEmpty() || index.region(region.get()).isEmpty()) {
                continue;
            }
            double target = bal.targetPerPlayer() * boosts.target(region.get());
            Location l = Locs.of(p);
            int around =
                    manager.frame()
                            .nearby(
                                    p.getWorld().getName(),
                                    l.getX(),
                                    l.getY(),
                                    l.getZ(),
                                    bal.showDistance())
                            .size();
            for (Recent r : recent) {
                if (r.world().equals(p.getWorld().getName())
                        && Steer.distanceSq(r.pos(), new Vec3(l.getX(), l.getY(), l.getZ()))
                                <= bal.showDistance() * bal.showDistance()) {
                    around += r.count();
                }
            }
            if (around >= target) {
                continue;
            }
            credits.put(pid, credit - 1);
            int made = attemptNatural(p, region.get(), bal, index);
            fishBudget -= made;
            total += made;
        }
        spawnCursor = (spawnCursor + 1) % Math.max(1, n);
    }

    private int attemptNatural(
            Player player, RegionId region, WildlifeBalance bal, ProfileIndex index) {
        List<Medium> mediums = new ArrayList<>(index.mediums(region));
        if (mediums.isEmpty()) {
            return 0;
        }
        Medium medium = mediums.get(random.nextInt(mediums.size()));
        World w = player.getWorld();
        MediumField field = kit.grid.field(w.getName());
        Location l = Locs.of(player);
        for (int attempt = 0; attempt < 3; attempt++) {
            double[] xz =
                    SpawnPlanner.ringPoint(
                            l.getX(), l.getZ(), bal.spawnRingMin(), bal.spawnRingMax(), random);
            int x = MediumField.floor(xz[0]);
            int z = MediumField.floor(xz[1]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int top;
            int bottom;
            if (medium == Medium.AIR) {
                double base = bal.airBaseY(region.id().name());
                top = (int) Math.floor(base + bal.airBandAbove());
                bottom = (int) Math.ceil(base - bal.airBandBelow());
            } else {
                top = l.getBlockY() + 12;
                bottom = l.getBlockY() - 28;
            }
            top = Math.min(top, w.getMaxHeight() - 1);
            bottom = Math.max(bottom, w.getMinHeight());
            Optional<SpawnPlanner.Spot> spot =
                    SpawnPlanner.column(
                            field,
                            medium,
                            x,
                            z,
                            top,
                            bottom,
                            bal.minLiquidDepth(),
                            bal.airClearance(),
                            random);
            if (spot.isEmpty()) {
                continue;
            }
            Vec3 pos = spot.get().pos();
            if (medium == Medium.FLOOR && !floorOk(w, spot.get(), bal)) {
                continue;
            }
            if (!SpawnPlanner.farFrom(pos, schoolCenters(w.getName(), pos, bal), bal.schoolGap())) {
                continue;
            }
            Location at = new Location(w, pos.x(), pos.y(), pos.z());
            Optional<RegionKeyResolver.RegionKey> key = kit.regions.fishingKey(at);
            if (key.isEmpty()) {
                continue;
            }
            RegionId keyRegion = key.get().region();
            SpawnTable.Conditions c =
                    new SpawnTable.Conditions(
                            keyRegion,
                            SpawnTable.timeKey(w.getTime()),
                            weather(w),
                            moon(w),
                            key.get().areas(),
                            flag -> kit.api.flags().hasServer(flag),
                            Set.of(medium),
                            spot.get().depth());
            Optional<SpeciesProfile> pick =
                    SpawnTable.pick(
                            index.region(keyRegion),
                            c,
                            bal::gradeWeight,
                            f -> boosts.weight(keyRegion, f),
                            random);
            if (pick.isEmpty()) {
                continue;
            }
            SpeciesProfile p = pick.get();
            int size = SpawnPlanner.schoolSize(p.schoolMin(), p.schoolMax(), random);
            return order(
                            p,
                            at,
                            size,
                            null,
                            WildFishSpawnEvent.Reason.NATURAL,
                            null,
                            keyRegion,
                            List.of())
                    .size();
        }
        return 0;
    }

    /** Floor fish: on seabed sand/gravel, at most 2 per chunk. */
    private boolean floorOk(World w, SpawnPlanner.Spot spot, WildlifeBalance bal) {
        int x = MediumField.floor(spot.pos().x());
        int z = MediumField.floor(spot.pos().z());
        Material below = w.getBlockAt(x, spot.floorY() - 1, z).getType();
        Set<String> allowed = Set.of(bal.floorBlocks().split(","));
        if (!allowed.contains(below.getKey().getKey())) {
            return false;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        int count = 0;
        for (FishOut o :
                manager.frame().nearby(w.getName(), cx * 16 + 8, spot.pos().y(), cz * 16 + 8, 12)) {
            if (o.profile().medium() == Medium.FLOOR
                    && MediumField.floor(o.x()) >> 4 == cx
                    && MediumField.floor(o.z()) >> 4 == cz) {
                count++;
            }
        }
        return count < bal.floorPerChunk();
    }

    private List<Vec3> schoolCenters(String world, Vec3 around, WildlifeBalance bal) {
        List<Vec3> centers = new ArrayList<>();
        for (FishOut o :
                manager.frame()
                        .nearby(world, around.x(), around.y(), around.z(), bal.schoolGap() + 2)) {
            if (o.school() == null || o.leader()) {
                centers.add(new Vec3(o.x(), o.y(), o.z()));
            }
        }
        for (Recent r : recent) {
            if (r.world().equals(world)) {
                centers.add(r.pos());
            }
        }
        return centers;
    }

    private static String weather(World w) {
        return w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear";
    }

    private static String moon(World w) {
        MoonPhase phase = w.getMoonPhase();
        return phase == MoonPhase.FULL_MOON ? "full" : phase == MoonPhase.NEW_MOON ? "new" : "";
    }

    /**
     * Places a spawn order (empty list if WildFishSpawnEvent is cancelled).
     *
     * @return IDs of the created fish (the first is the leader)
     */
    List<UUID> order(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour scripted,
            WildFishSpawnEvent.Reason reason,
            @Nullable String tag,
            @Nullable RegionId region,
            List<Vec3> path) {
        World w = at.getWorld();
        if (w == null || count <= 0) {
            return List.of();
        }
        WildFishSpawnEvent event =
                new WildFishSpawnEvent(p.fish().id(), at, count, p.medium(), reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        UUID school = count > 1 ? UUID.randomUUID() : null;
        RegionId home = region != null ? region : p.fish().region();
        Vec3 center = new Vec3(at.getX(), at.getY(), at.getZ());
        SpawnOrder o =
                new SpawnOrder(
                        ids,
                        p,
                        w.getName(),
                        center,
                        school,
                        scripted,
                        path,
                        tag,
                        reason == WildFishSpawnEvent.Reason.SCRIPTED
                                        || reason == WildFishSpawnEvent.Reason.PATH
                                ? null
                                : home.toString(),
                        kit.balance().airBaseY(home.id().name()));
        engine.submit(world -> world.spawn(o));
        recent.add(new Recent(w.getName(), center, count, manager.frame().seq(), tag));
        return ids;
    }

    /**
     * If the location is outside the medium, finds a medium cell within 3 cells up/down to spawn
     * in.
     */
    List<UUID> spawnAt(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour behaviour,
            WildFishSpawnEvent.Reason reason,
            List<Vec3> path) {
        World w = at.getWorld();
        if (w == null) {
            return List.of();
        }
        MediumField field = kit.grid.field(w.getName());
        Medium cell = p.medium().cell();
        Location spot = null;
        for (int dy : new int[] {0, -1, 1, -2, 2, -3, 3}) {
            Location c = at.clone().add(0, dy, 0);
            if (field.is(cell, c.getX(), c.getY(), c.getZ())) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            return List.of();
        }
        RegionId region = kit.regions.regionAt(spot).map(RegionInfo::id).orElse(null);
        return order(p, spot, count, behaviour, reason, null, region, path);
    }

    /** School swimming along a path (count of 0 or less picks from the species' school size). */
    List<UUID> spawnSchool(SpeciesProfile p, List<Location> path, int count) {
        List<Vec3> points = new ArrayList<>();
        for (Location l : path.subList(1, path.size())) {
            points.add(new Vec3(l.getX(), l.getY(), l.getZ()));
        }
        int n = count > 0 ? count : SpawnPlanner.schoolSize(p.schoolMin(), p.schoolMax(), random);
        return spawnAt(p, path.getFirst(), n, null, WildFishSpawnEvent.Reason.PATH, points);
    }

    // ---------------------------------------------------------------- fish schools

    void spotMoved(FishingSpot spot, @Nullable FishingSpot previous) {
        if (previous != null) {
            anchors.remove(previous.id());
        }
        if (!spot.hidden()) {
            anchors.put(spot.id(), spot);
        }
    }

    /**
     * Places one extra school of the region's species at a fish school spot (only when a player is
     * nearby).
     */
    void spotPass(WildlifeBalance bal) {
        if (anchors.isEmpty()) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (FishOut o : manager.frame().fish()) {
            String tag = o.tag();
            if (tag != null) {
                present.add(tag);
            }
        }
        for (Recent r : recent) {
            String tag = r.tag();
            if (tag != null) {
                present.add(tag);
            }
        }
        ProfileIndex index = kit.index();
        for (FishingSpot spot : anchors.values()) {
            if (present.contains(spot.id()) || index.region(spot.region()).isEmpty()) {
                continue;
            }
            World w = actualWorld(spot.world());
            if (w == null
                    || !w.isChunkLoaded(
                            MediumField.floor(spot.x()) >> 4, MediumField.floor(spot.z()) >> 4)) {
                continue;
            }
            Location center = new Location(w, spot.x(), spot.y(), spot.z());
            double show2 = bal.showDistance() * bal.showDistance();
            boolean watched = false;
            for (Player p : w.getPlayers()) {
                if (Locs.of(p).distanceSquared(center) <= show2) {
                    watched = true;
                    break;
                }
            }
            if (!watched) {
                continue;
            }
            int x = MediumField.floor(spot.x());
            int z = MediumField.floor(spot.z());
            Optional<SpawnPlanner.Spot> found =
                    SpawnPlanner.column(
                            kit.grid.field(w.getName()),
                            Medium.WATER,
                            x,
                            z,
                            (int) spot.y() + 4,
                            (int) spot.y() - 20,
                            bal.minLiquidDepth(),
                            bal.airClearance(),
                            random);
            if (found.isEmpty()) {
                continue;
            }
            Vec3 pos = found.get().pos();
            Location at = new Location(w, pos.x(), pos.y(), pos.z());
            SpawnTable.Conditions c =
                    new SpawnTable.Conditions(
                            spot.region(),
                            SpawnTable.timeKey(w.getTime()),
                            weather(w),
                            moon(w),
                            kit.regions.areasAt(at),
                            flag -> kit.api.flags().hasServer(flag),
                            Set.of(Medium.WATER),
                            found.get().depth());
            Optional<SpeciesProfile> pick =
                    SpawnTable.pick(
                            index.region(spot.region()),
                            c,
                            bal::gradeWeight,
                            f -> boosts.weight(spot.region(), f),
                            random);
            pick.ifPresent(
                    p ->
                            order(
                                    p,
                                    at,
                                    Math.max(p.schoolMax(), p.schoolMin()),
                                    null,
                                    WildFishSpawnEvent.Reason.SPOT,
                                    spot.id(),
                                    spot.region(),
                                    List.of()));
        }
    }

    private @Nullable World actualWorld(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return direct;
        }
        for (World w : Bukkit.getWorlds()) {
            if (kit.contentWorld(w).equals(contentWorld)) {
                return w;
            }
        }
        return null;
    }

    int anchorCount() {
        return anchors.size();
    }
}
