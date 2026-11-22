package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One hall of fame line (hall_of_fame row).
 *
 * @param subject subject ID string (species or boss ID, "-" if no subject)
 * @param finalized whether finalized by the season close (false = provisional record in progress)
 */
public record HallOfFameEntry(
        int season,
        String category,
        String subject,
        int rank,
        UUID player,
        String nameAtTime,
        double value,
        Map<String, String> detail,
        Instant recordedAt,
        boolean finalized) {

    /** subject value for categories without a subject. */
    public static final String NO_SUBJECT = "-";

    public HallOfFameEntry {
        detail = Map.copyOf(detail);
    }
}
