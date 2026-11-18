package wiki.creeper.harbor.npc.content;

import java.util.List;
import java.util.Set;

/** 시간대·날씨 키. Fishing과 같은 값을 쓴다 (dawn·day·dusk·night / clear·rain·thunder, storm은 thunder와 같음). */
public final class Conditions {

    public static final Set<String> TIMES = Set.of("dawn", "day", "dusk", "night");

    public static final Set<String> WEATHERS = Set.of("clear", "rain", "thunder", "storm");

    private Conditions() {}

    /** 월드 시간(0~23999) → 시간대 키. Fishing FishingManager와 같은 경계. */
    public static String timeKey(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        return t >= 23000 || t < 1000 ? "dawn" : t < 12000 ? "day" : t < 13800 ? "dusk" : "night";
    }

    public static String weatherKey(boolean thundering, boolean storm) {
        return thundering ? "thunder" : storm ? "rain" : "clear";
    }

    /** 비어 있으면 항상 참. storm은 thunder로 본다. */
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

    /** 컷씬 time 키워드 → 개인 시간 틱. */
    public static long ticksOf(String time) {
        return switch (time) {
            case "dawn" -> 23_500L;
            case "dusk" -> 12_500L;
            case "night" -> 18_000L;
            default -> 6_000L;
        };
    }
}
