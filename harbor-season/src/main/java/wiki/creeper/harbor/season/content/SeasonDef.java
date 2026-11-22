package wiki.creeper.harbor.season.content;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * One season definition (season/seasons.yml, docs 20 11.3).
 *
 * @param startDate start date
 * @param startTime start time (null = that day's daily reset time)
 * @param weeks season length (weeks, default 12 = '7. Balance design' ch. 1)
 * @param themeRegion theme region (null if none)
 * @param packUrl season resource pack URL (null if none)
 * @param packSha1 resource pack SHA-1 (40 hex digits, required if packUrl is set)
 * @param contentDir season content folder (relative to the content root, null if none)
 */
public record SeasonDef(
        int season,
        String name,
        LocalDate startDate,
        @Nullable LocalTime startTime,
        int weeks,
        @Nullable RegionId themeRegion,
        @Nullable String packUrl,
        @Nullable String packSha1,
        @Nullable String contentDir) {

    /** Start time. Uses dailyReset if startTime is not set. */
    public ZonedDateTime startsAt(ZoneId zone, LocalTime dailyReset) {
        LocalTime time = startTime;
        return startDate.atTime(time == null ? dailyReset : time).atZone(zone);
    }
}
