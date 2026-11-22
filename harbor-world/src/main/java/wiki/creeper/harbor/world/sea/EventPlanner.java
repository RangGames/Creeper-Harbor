package wiki.creeper.harbor.world.sea;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.content.SeaEventDef;

/**
 * Sea event schedule calculation (docs 20 8.4 'sea event scheduler' steps 1–2). Uses a random
 * number seeded by the date and event ID (FNV-1a), so any server computing it at any time gets the
 * same result: whether it fires that day (chance), the start time within the window, and which
 * candidate region. The schedule stays stable even if the lock-holding server changes. No server
 * API (unit tests).
 */
public final class EventPlanner {

    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private EventPlanner() {}

    /**
     * Plan of one run.
     *
     * @param runId run ID (<event name>_<start time yyyyMMddHHmm>), also used as the Fishing mythic
     *     counter key
     * @param region waters (null = whole server)
     */
    public record Plan(
            String runId,
            SeaEventDef def,
            @Nullable RegionId region,
            Instant warnAt,
            Instant startAt,
            Instant endAt) {

        public boolean isOpen(Instant now) {
            return !now.isBefore(warnAt) && now.isBefore(endAt);
        }
    }

    /**
     * The run whose window opens on this date (empty if none). Only looks at chance and weekday
     * (minimum interval, flags and season count are checked by {@link #allowed}).
     */
    public static Optional<Plan> plan(SeaEventDef def, LocalDate date, ZoneId zone) {
        SeaEventDef.Window window = def.window();
        if (!window.days().contains(date.getDayOfWeek())) {
            return Optional.empty();
        }
        String seed = def.id() + "@" + date;
        if (unit(seed + "#roll") >= def.chance()) {
            return Optional.empty();
        }
        int windowMinutes = window.minutes();
        int maxOffset =
                def.durationMinutes() < windowMinutes ? windowMinutes - def.durationMinutes() : 0;
        int offset = (int) Math.floor(unit(seed + "#start") * (maxOffset + 1));
        ZonedDateTime start = date.atTime(window.start()).atZone(zone).plusMinutes(offset);
        RegionId region = null;
        if (!def.regions().isEmpty()) {
            int pick =
                    (int) Math.floor(unit(seed + "#region") * def.regions().size())
                            % def.regions().size();
            region = def.regions().get(pick);
        }
        Instant startAt = start.toInstant();
        return Optional.of(
                new Plan(
                        def.key() + "_" + RUN.format(start),
                        def,
                        region,
                        startAt.minus(Duration.ofMinutes(def.warnMinutes())),
                        startAt,
                        startAt.plus(Duration.ofMinutes(def.durationMinutes()))));
    }

    /** Runs that should be open now (including yesterday's window continuing past midnight). */
    public static List<Plan> openPlans(SeaEventDef def, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Plan> result = new ArrayList<>();
        for (LocalDate d : List.of(today.minusDays(1), today, today.plusDays(1))) {
            plan(def, d, zone).filter(p -> p.isOpen(now)).ifPresent(result::add);
        }
        return result;
    }

    /**
     * Checks the minimum interval, flag and season count.
     *
     * @param lastStart start of the previous run of the same event (null if none)
     * @param seasonRuns number of runs this season
     * @param flagSet whether requires_flag is set (true if there is no condition)
     */
    public static boolean allowed(
            Plan plan, @Nullable Instant lastStart, int seasonRuns, boolean flagSet) {
        SeaEventDef def = plan.def();
        if (!flagSet) {
            return false;
        }
        if (def.maxPerSeason() > 0 && seasonRuns >= def.maxPerSeason()) {
            return false;
        }
        return lastStart == null
                || !plan.startAt().isBefore(lastStart.plus(Duration.ofHours(def.minGapHours())));
    }

    /** String → [0, 1) (FNV-1a 64-bit + mixing). */
    static double unit(String seed) {
        long h = 0xcbf29ce484222325L;
        for (byte b : seed.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
