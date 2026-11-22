package wiki.creeper.harbor.world.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * One ship (ships row).
 *
 * @param type ship definition ID (ship/harbor_trawler …)
 * @param upgrades step per path (0–3)
 * @param maxDurability max durability including hull upgrades
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
