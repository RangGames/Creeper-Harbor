package wiki.creeper.harbor.collection.aquarium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.collection.content.AquariumRules.Tank;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Tank layout (docs 20 4.4 "tank display"). One rig per donated species. Deep-sea species go to
 * deep-sea hall tanks, the rest fill regular tanks in donation order. Visible rigs are capped at
 * visibleMax; beyond that, the window slides by visibleMax every rotation (rotation count) to show
 * the rest in turn. Uses no server API.
 */
public final class TankLayout {

    private TankLayout() {}

    /**
     * @param species donated species (in donation order)
     * @param tanks open tanks
     * @param deep species to place in deep-sea hall tanks
     * @return species → tank id (shown species only, order preserved)
     */
    public static Map<FishId, String> assign(
            List<FishId> species,
            List<Tank> tanks,
            Set<FishId> deep,
            int visibleMax,
            long rotation) {
        Map<FishId, String> result = new LinkedHashMap<>();
        if (tanks.isEmpty() || species.isEmpty() || visibleMax <= 0) {
            return result;
        }
        List<FishId> shown = window(species, visibleMax, rotation);
        Map<String, Integer> used = new LinkedHashMap<>();
        boolean hasDeep = tanks.stream().anyMatch(Tank::deep);
        for (FishId fish : shown) {
            boolean wantsDeep = hasDeep && deep.contains(fish);
            for (Tank tank : tanks) {
                if (tank.deep() != wantsDeep) {
                    continue;
                }
                int n = used.getOrDefault(tank.id(), 0);
                if (n < tank.slots()) {
                    used.put(tank.id(), n + 1);
                    result.put(fish, tank.id());
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Rotation window: everything if total ≤ visibleMax; otherwise visibleMax entries starting at
     * rotation × visibleMax (wrapping from the end to the start).
     */
    public static List<FishId> window(List<FishId> species, int visibleMax, long rotation) {
        int n = species.size();
        if (n <= visibleMax) {
            return List.copyOf(species);
        }
        int start = (int) Math.floorMod(rotation * visibleMax, (long) n);
        List<FishId> result = new ArrayList<>(visibleMax);
        for (int i = 0; i < visibleMax; i++) {
            result.add(species.get((start + i) % n));
        }
        return result;
    }
}
