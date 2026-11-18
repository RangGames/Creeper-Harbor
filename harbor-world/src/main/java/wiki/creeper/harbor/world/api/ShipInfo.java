package wiki.creeper.harbor.world.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 선박 한 척 (ships 행).
 *
 * @param type 선박 정의 ID (ship/harbor_trawler …)
 * @param upgrades 갈래별 단계 (0~3)
 * @param maxDurability 선체 업그레이드를 반영한 최대 내구도
 */
public record ShipInfo(
        long shipId,
        UUID owner,
        HarborId type,
        String name,
        Map<UpgradePath, Integer> upgrades,
        int durability,
        int maxDurability,
        ShipState state,
        Set<ShipFlag> flags) {

    public ShipInfo {
        upgrades = Map.copyOf(upgrades);
        flags = Set.copyOf(flags);
    }

    public int level(UpgradePath path) {
        return upgrades.getOrDefault(path, 0);
    }

    public boolean has(ShipFlag flag) {
        return flags.contains(flag);
    }
}
