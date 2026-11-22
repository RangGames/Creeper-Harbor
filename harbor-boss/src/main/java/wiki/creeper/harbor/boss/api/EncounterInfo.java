package wiki.creeper.harbor.boss.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.id.BossId;

/**
 * Read-only view of one encounter (values at call time).
 *
 * @param members participants (party leader first). For world bosses, everyone who has contributed
 *     so far
 * @param roles participant roles
 * @param slot arena slot number (0 for world bosses)
 * @param hpRatio HP ratio (rage/explosion gauge for calm bosses) 1 → 0
 */
public record EncounterInfo(
        UUID id,
        BossId boss,
        EncounterState state,
        UUID leader,
        List<UUID> members,
        Map<UUID, BossRole> roles,
        String server,
        int slot,
        double hpRatio,
        int hull,
        int hullMax,
        long elapsedTicks) {

    public EncounterInfo {
        members = List.copyOf(members);
        roles = Map.copyOf(roles);
    }
}
