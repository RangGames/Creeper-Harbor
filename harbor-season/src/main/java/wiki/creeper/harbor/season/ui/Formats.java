package wiki.creeper.harbor.season.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.content.RankingBoards;
import wiki.creeper.harbor.season.hof.HofMath;

/** Display formats. */
public final class Formats {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private Formats() {}

    public static String date(Instant at, ZoneId zone) {
        return at.equals(Instant.EPOCH) ? "-" : DATE.format(at.atZone(zone));
    }

    /** Ranking value display (per source). */
    public static String rankValue(String source, RankEntry entry) {
        return switch (source) {
            case RankingBoards.RECORDS -> HofMath.format(entry.value(), "cm");
            case RankingBoards.LEVEL ->
                    "Lv." + (long) entry.value() + " (" + (long) entry.secondary() + " EXP)";
            case RankingBoards.COLLECTION_SEASON -> (long) entry.value() + "종";
            case RankingBoards.BOSS_RECORDS -> HofMath.format(entry.value(), "time_ms");
            default -> HofMath.format(entry.value(), "");
        };
    }
}
