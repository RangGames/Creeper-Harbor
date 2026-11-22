package wiki.creeper.harbor.npc.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.npc.Worlds;

/** NPC gaze: each NPC looks at the nearest player within its look radius (main thread). */
final class NpcLook {

    private NpcLook() {}

    /**
     * @param forced gaze override per NPC (null = look at the nearest player)
     */
    static void tick(
            Collection<SpawnedNpc> spawned,
            Map<String, Map<Long, List<SpawnedNpc>>> index,
            Function<SpawnedNpc, @Nullable Location> forced) {
        if (spawned.isEmpty()) {
            return;
        }
        Map<SpawnedNpc, Player> nearest = new HashMap<>();
        Map<SpawnedNpc, Double> best = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<Long, List<SpawnedNpc>> chunks = index.get(player.getWorld().getName());
            if (chunks == null) {
                continue;
            }
            Location loc = Worlds.loc(player);
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<SpawnedNpc> list = chunks.get(SpawnedNpc.chunkKey(cx + dx, cz + dz));
                    if (list == null) {
                        continue;
                    }
                    for (SpawnedNpc npc : list) {
                        double r = npc.def().lookRadius();
                        double d = npc.home().distanceSquared(loc);
                        if (d <= r * r && d < best.getOrDefault(npc, Double.MAX_VALUE)) {
                            best.put(npc, d);
                            nearest.put(npc, player);
                        }
                    }
                }
            }
        }
        for (SpawnedNpc npc : spawned) {
            Location fixed = forced.apply(npc);
            if (fixed != null) {
                npc.body().lookAt(fixed);
                continue;
            }
            Player target = nearest.get(npc);
            if (target != null) {
                npc.body().lookAt(Worlds.eye(target));
            } else {
                npc.body().resetLook();
            }
        }
    }
}
