package wiki.creeper.harbor.npc.content;

import java.util.List;
import java.util.Set;

/**
 * Time-of-day and weather keys. Same values as Fishing (dawn, day, dusk, night / clear, rain,
 * thunder; storm equals thunder).
 */
public final class Conditions {

    public static final Set<String> TIMES = Set.of("dawn", "day", "dusk", "night");

    public static final Set<String> WEATHERS = Set.of("clear", "rain", "thunder", "storm");

    private Conditions() {}

    /** World time (0-23999) to time-of-day key. Same boundaries as Fishing FishingManager. */
    public static String timeKey(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        return t >= 23000 || t < 1000 ? "dawn" : t < 12000 ? "day" : t < 13800 ? "dusk" : "night";
    }

    public static String weatherKey(boolean thundering, boolean storm) {
        return thundering ? "thunder" : storm ? "rain" : "clear";
    }

    /** Always true if empty. storm counts as thunder. */
    public static boolean matches(List<String> allowed, String actual) {
        if (allowed.isEmpty()) {
            return true;
        }
        for (String a : allowed) {
            String normalized = a.equals("storm") ? "thunder" : a;
            if (normalized.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    /** Cutscene time keyword to personal time ticks. */
    public static long ticksOf(String time) {
        return switch (time) {
            case "dawn" -> 23_500L;
            case "dusk" -> 12_500L;
            case "night" -> 18_000L;
            default -> 6_000L;
        };
    }
}
