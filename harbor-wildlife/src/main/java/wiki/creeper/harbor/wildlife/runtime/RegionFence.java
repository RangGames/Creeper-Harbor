package wiki.creeper.harbor.wildlife.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.Frame;
import wiki.creeper.harbor.world.api.RegionInfo;

/** 지역 경계: 자기 지역을 벗어난 개체를 계산 스레드에 되돌리라고 알린다. 메인 스레드 전용. */
final class RegionFence {

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final Map<UUID, Long> turnedBack = new HashMap<>();
    private int regionCursor;

    RegionFence(WildlifeKit kit, SimEngine engine) {
        this.kit = kit;
        this.engine = engine;
    }

    /** 지역 경계를 넘은 개체는 방향을 돌린다 (틱당 일부만 차례로 확인). */
    void check(Frame applied, long tick) {
        List<FishOut> list = applied.fish();
        int size = list.size();
        if (size == 0) {
            return;
        }
        int n = Math.min(100, size);
        for (int i = 0; i < n; i++) {
            FishOut o = list.get((regionCursor + i) % size);
            String home = o.region();
            if (home == null || o.hidden()) {
                continue;
            }
            Long last = turnedBack.get(o.id());
            if (last != null && tick - last < 40) {
                continue;
            }
            World w = Bukkit.getWorld(o.world());
            if (w == null) {
                continue;
            }
            Optional<RegionInfo> r = kit.regions.regionAt(new Location(w, o.x(), o.y(), o.z()));
            if (r.isEmpty() || !r.get().id().toString().equals(home)) {
                turnedBack.put(o.id(), tick);
                UUID id = o.id();
                engine.submit(world -> world.turnBack(id));
            }
        }
        regionCursor = (regionCursor + n) % size;
    }

    /** 10초 넘게 지난 되돌림 기록을 지운다. */
    void prune(long tick) {
        turnedBack.values().removeIf(t -> tick - t > 200);
    }

    void forget(UUID id) {
        turnedBack.remove(id);
    }
}
