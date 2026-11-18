package wiki.creeper.harbor.collection.aquarium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.collection.content.AquariumRules.Tank;
import wiki.creeper.harbor.common.id.FishId;

/**
 * 수조 배치 (docs 20 4.4 '수조 연출'). 기부된 어종마다 리그 1개. 심해 어종은 심해관 수조, 나머지는 일반 수조에 먼저 기부된 순서로 채운다. 동시에 보이는
 * 리그는 visibleMax로 제한하고, 넘치면 rotation(교대 횟수)마다 창을 visibleMax만큼 밀어 나머지를 교대로 보여준다. 서버 API를 쓰지 않는다.
 */
public final class TankLayout {

    private TankLayout() {}

    /**
     * @param species 기부된 어종 (먼저 기부된 순)
     * @param tanks 열린 수조
     * @param deep 심해관 수조에 넣을 어종
     * @return 어종 → 수조 id (보여줄 어종만, 순서 유지)
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

    /** 교대 창: 전체가 visibleMax 이하면 전부, 넘치면 rotation × visibleMax 위치부터 visibleMax개 (끝에서 처음으로 이어짐). */
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
