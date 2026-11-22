package wiki.creeper.harbor.boss.api;

import wiki.creeper.harbor.common.id.BossId;

/**
 * World boss progress (same as the harbor:worldboss broadcast payload).
 *
 * @param eventId ID of this world boss (encounter ID)
 * @param stage 0-4 (25% stages: 0 = start, 1 = 75% or less, 2 = 50% or less, 3 = 25% or less, 4 =
 *     end)
 * @param percent remaining gauge (100 → 0)
 * @param participants number of valid participants (contributed in the last 60 seconds)
 * @param server running server
 */
public record WorldBossStatus(
        BossId boss,
        String eventId,
        int stage,
        double percent,
        int participants,
        String server,
        EncounterState state) {}
