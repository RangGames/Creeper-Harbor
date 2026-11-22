package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One ranking line.
 *
 * @param rank starting at 1. On equal values, whoever achieved it first ranks higher (no ties)
 * @param uuid player. Party leader for party records (fastest boss kill)
 * @param name display name (text like "leader and N others" for parties is built by the screen)
 * @param value score (size in cm, level, codex count, kill time in ms ...)
 * @param secondary secondary value (experience for the level board etc., 0 if none)
 * @param achievedAt time achieved (tie-breaker)
 * @param members number of members in a party record (1 for personal records)
 */
public record RankEntry(
        int rank,
        @Nullable UUID uuid,
        String name,
        double value,
        double secondary,
        Instant achievedAt,
        int members) {}
