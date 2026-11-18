package wiki.creeper.harbor.boss.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.BossId;

/**
 * 보스별 최단 처치 기록 (boss_records, 시즌별 1~10위만 유지).
 *
 * @param members 파티장 먼저
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
