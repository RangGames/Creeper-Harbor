package wiki.creeper.harbor.boss.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.BossId;

/**
 * Fastest kill record per boss (boss_records, only ranks 1-10 kept per season).
 *
 * @param members party leader first
 */
public record BossRecord(
        BossId boss,
        int season,
        long durationMs,
        UUID encounterId,
        List<UUID> members,
        Instant clearedAt) {

    public BossRecord {
        members = List.copyOf(members);
    }
}
