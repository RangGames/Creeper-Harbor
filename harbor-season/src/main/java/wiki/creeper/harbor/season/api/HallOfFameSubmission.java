package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Provisional hall of fame record submission (docs 20 11.4).
 *
 * @param category category in hall_of_fame.yml (e.g. {@code biggest_fish}, {@code fastest_boss})
 * @param subject subject (species ID, boss ID). null if the category has no subject
 * @param player player who set the record (party leader for party records)
 * @param name name at the time of the record (for parties, display text such as "leader and N
 *     others")
 * @param value value (size in cm, kill time in ms ...). Better or worse is decided by the
 *     category's order
 * @param detail details (key-value pairs shown in the Dialog, e.g. quality=5, members=uuid,uuid)
 * @param achievedAt time achieved (on ties, the record achieved first ranks higher)
 */
public record HallOfFameSubmission(
        String category,
        @Nullable HarborId subject,
        UUID player,
        String name,
        double value,
        Map<String, String> detail,
        Instant achievedAt) {

    public HallOfFameSubmission {
        detail = Map.copyOf(detail);
    }

    /** Collection: new server biggest catch for a species (top across all leagues). */
    public static HallOfFameSubmission biggestFish(
            HarborId fish, UUID player, String name, double sizeCm, Instant caughtAt) {
        return new HallOfFameSubmission(
                "biggest_fish", fish, player, name, sizeCm, Map.of(), caughtAt);
    }

    /** Boss: fastest kill record of a CLEARED encounter. */
    public static HallOfFameSubmission fastestBoss(
            HarborId boss,
            UUID leader,
            String name,
            long durationMs,
            Map<String, String> detail,
            Instant clearedAt) {
        return new HallOfFameSubmission(
                "fastest_boss", boss, leader, name, durationMs, detail, clearedAt);
    }
}
