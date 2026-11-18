package wiki.creeper.harbor.world.spot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.FishingSpotService;
import wiki.creeper.harbor.world.api.event.FishingSpotMovedEvent;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 물고기 떼 포인트 (docs 20 8.4 '물고기 떼 포인트'). 지역마다 fishing_spots개를 spot_candidates에서 골라 두고 10~20분마다 다른 후보로
 * 옮긴다. 반경 6블록 안 캐스팅이면 RegionKeyResolver 결과에 물고기 떼 표시를 싣고, 던진 사람에게 bite_wait ×0.8 · rare_weight
 * +0.10 보정자 (출처 spot:…, 45초)를 단다 (희귀 이상 보정 상한 +60%는 Fishing이 적용). 입질 대기는 던질 때 정해지므로 같은 자리에서 다시 던질
 * 때부터 적용된다. 파티클은 반경 32블록 안 플레이어에게만, 숨은 낚시터는 탐험 2차 '숨은 낚시터 감지'(feature_hidden_spot)가 있는 플레이어에게만 보낸다.
 * 이 서버에 월드가 있는 지역만 다룬다. 메인 스레드 전용.
 */
public final class SpotManager implements FishingSpotService {

    private static final StatKey RARE = StatKey.of("rare_weight");
    private static final StatKey BITE = StatKey.of("bite_wait");

    private final WorldKit kit;
    private final Map<RegionId, List<FishingSpot>> spots = new HashMap<>();
    private final Map<UUID, String> appliedSource = new HashMap<>();

    public SpotManager(WorldKit kit) {
        this.kit = kit;
    }

    public void start() {
        var _ = kit.tasks().timer(40, 20, this::tick);
        var _ = kit.tasks().timer(45, 40, this::particles);
    }

    /** 리로드 뒤: 후보가 바뀐 지역은 다시 고른다. */
    public void reset() {
        spots.clear();
        tick();
    }

    private void tick() {
        Instant now = Instant.now();
        for (WorldIndex.Entry e : kit.index().regions()) {
            if (kit.bukkitWorld(e.def().world()) == null) {
                spots.remove(e.id());
                continue;
            }
            List<FishingSpot> current = spots.get(e.id());
            if (current == null) {
                current = new ArrayList<>(place(e, now));
                spots.put(e.id(), current);
                current.forEach(
                        s ->
                                Bukkit.getPluginManager()
                                        .callEvent(new FishingSpotMovedEvent(s, null)));
                continue;
            }
            for (int i = 0; i < current.size(); i++) {
                FishingSpot s = current.get(i);
                if (s.hidden() || now.isBefore(s.movesAt())) {
                    continue;
                }
                FishingSpot moved = move(e, s, current, now);
                current.set(i, moved);
                appliedSource.values().removeIf(src -> src.equals(s.id()));
                Bukkit.getPluginManager().callEvent(new FishingSpotMovedEvent(moved, s));
            }
        }
    }

    private List<FishingSpot> place(WorldIndex.Entry e, Instant now) {
        List<RegionExt.Point> candidates = new ArrayList<>(e.ext().spotCandidates());
        java.util.Collections.shuffle(candidates, ThreadLocalRandom.current());
        int count = Math.min(e.def().fishingSpots(), candidates.size());
        List<FishingSpot> result = new ArrayList<>();
        String name = e.id().id().name();
        for (int i = 0; i < count; i++) {
            RegionExt.Point p = candidates.get(i);
            result.add(spot("spot:" + name + "_" + i, e, p, false, nextMove(now)));
        }
        for (RegionExt.HiddenSpot h : e.ext().hiddenSpots()) {
            result.add(spot("spot:" + h.id(), e, h.at(), true, Instant.MAX));
        }
        return result;
    }

    private FishingSpot move(
            WorldIndex.Entry e, FishingSpot old, List<FishingSpot> taken, Instant now) {
        Set<String> used = new HashSet<>();
        taken.forEach(s -> used.add(s.x() + "," + s.z()));
        List<RegionExt.Point> free = new ArrayList<>();
        for (RegionExt.Point p : e.ext().spotCandidates()) {
            if (!used.contains(p.x() + "," + p.z())) {
                free.add(p);
            }
        }
        if (free.isEmpty()) {
            return new FishingSpot(
                    old.id(),
                    old.region(),
                    old.world(),
                    old.x(),
                    old.y(),
                    old.z(),
                    false,
                    nextMove(now));
        }
        RegionExt.Point p = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        return spot(old.id(), e, p, false, nextMove(now));
    }

    private static FishingSpot spot(
            String id, WorldIndex.Entry e, RegionExt.Point p, boolean hidden, Instant movesAt) {
        return new FishingSpot(id, e.id(), e.def().world(), p.x(), p.y(), p.z(), hidden, movesAt);
    }

    private Instant nextMove(Instant now) {
        WorldBalance b = kit.balance();
        int min = b.spotMoveMinSeconds();
        int max = Math.max(min, b.spotMoveMaxSeconds());
        return now.plusSeconds(min + ThreadLocalRandom.current().nextInt(max - min + 1));
    }

    private void particles() {
        WorldBalance b = kit.balance();
        double radius = b.spotParticleRadius();
        double radar = b.spotRadarRadius();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location at = WorldKit.loc(player);
            String worldId = kit.worldId(at);
            boolean hiddenSense = kit.stat(player, "feature_hidden_spot", 0) > 0;
            boolean radarSense = kit.stat(player, "feature_school_radar", 0) > 0;
            double reach = radarSense ? Math.max(radius, radar) : radius;
            for (List<FishingSpot> list : spots.values()) {
                for (FishingSpot s : list) {
                    if (!s.world().equals(worldId) || (s.hidden() && !hiddenSense)) {
                        continue;
                    }
                    if (s.distanceSquared(at.getX(), at.getZ()) > reach * reach) {
                        continue;
                    }
                    World world = player.getWorld();
                    Location p = new Location(world, s.x(), s.y() + 0.1, s.z());
                    player.spawnParticle(
                            s.hidden() ? Particle.GLOW : Particle.BUBBLE_POP,
                            p,
                            12,
                            1.5,
                            0.05,
                            1.5,
                            0.02);
                    player.spawnParticle(Particle.FISHING, p, 6, 1.2, 0.0, 1.2, 0.01);
                }
            }
        }
    }

    // ------------------------------------------------------------------ FishingSpotService

    @Override
    public Optional<FishingSpot> spotNear(Location location, double radius) {
        return near(location, radius, false);
    }

    @Override
    public Optional<FishingSpot> spotNear(Player viewer, Location location, double radius) {
        return near(location, radius, kit.stat(viewer, "feature_hidden_spot", 0) > 0);
    }

    private Optional<FishingSpot> near(Location location, double radius, boolean hidden) {
        String worldId = kit.worldId(location);
        FishingSpot best = null;
        double bestD = radius * radius;
        for (List<FishingSpot> list : spots.values()) {
            for (FishingSpot s : list) {
                if (!s.world().equals(worldId) || (s.hidden() && !hidden)) {
                    continue;
                }
                double d = s.distanceSquared(location.getX(), location.getZ());
                if (d <= bestD) {
                    best = s;
                    bestD = d;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public List<FishingSpot> spots(RegionId region) {
        return List.copyOf(spots.getOrDefault(region, List.of()));
    }

    public List<FishingSpot> all() {
        List<FishingSpot> result = new ArrayList<>();
        spots.values().forEach(result::addAll);
        return result;
    }

    /** 관리 명령: 지금 바로 옮긴다. */
    public int moveNow(RegionId region) {
        List<FishingSpot> list = spots.get(region);
        if (list == null) {
            return 0;
        }
        List<FishingSpot> expired = new ArrayList<>();
        for (FishingSpot s : list) {
            expired.add(
                    s.hidden()
                            ? s
                            : new FishingSpot(
                                    s.id(),
                                    s.region(),
                                    s.world(),
                                    s.x(),
                                    s.y(),
                                    s.z(),
                                    false,
                                    Instant.EPOCH));
        }
        spots.put(region, expired);
        tick();
        return expired.size();
    }

    /** 찌 자리의 물고기 떼 + 던진 사람 보정자 (RegionManager 훅). */
    public Optional<FishingSpot> school(@Nullable Player shooter, Location bobber) {
        double radius = kit.balance().spotRadius();
        Optional<FishingSpot> spot =
                shooter == null ? spotNear(bobber, radius) : spotNear(shooter, bobber, radius);
        if (spot.isPresent() && shooter != null) {
            applyBonus(shooter, spot.get());
        }
        return spot;
    }

    private void applyBonus(Player shooter, FishingSpot spot) {
        UUID uuid = shooter.getUniqueId();
        WorldBalance b = kit.balance();
        String previous = appliedSource.put(uuid, spot.id());
        if (previous != null) {
            kit.api().modifiers().removeSource(uuid, previous);
        }
        Instant until = Instant.now().plusSeconds(b.spotModifierSeconds());
        kit.api()
                .modifiers()
                .add(uuid, Modifier.add(RARE, b.spotRareWeight(), spot.id()).until(until));
        kit.api()
                .modifiers()
                .add(uuid, Modifier.multiply(BITE, b.spotBiteWait(), spot.id()).until(until));
    }

    public void forget(UUID player) {
        appliedSource.remove(player);
    }
}
