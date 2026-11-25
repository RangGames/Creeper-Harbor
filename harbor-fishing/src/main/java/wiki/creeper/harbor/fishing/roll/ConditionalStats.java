package wiki.creeper.harbor.fishing.roll;

import java.util.Map;
import java.util.Set;

/**
 * Conditional gear stats {@code <stat>.<token>[+<token>…]} (proposed encoding, items/*.yml stats):
 * the value counts only when every token is among the given tokens, e.g. glow float {@code
 * bite_wait.night: -0.10} (night bite wait −10%). No server API (unit tests).
 */
public final class ConditionalStats {

    private ConditionalStats() {}

    /** Sum of the conditional entries of a stat whose tokens are all present. */
    public static double sum(Map<String, Double> gear, String stat, Set<String> tokens) {
        String prefix = stat + ".";
        double sum = 0;
        for (Map.Entry<String, Double> e : gear.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(prefix)
                    && key.length() > prefix.length()
                    && matches(key.substring(prefix.length()), tokens)) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    private static boolean matches(String condition, Set<String> tokens) {
        for (String token : condition.split("\\+", -1)) {
            if (!token.isBlank() && !tokens.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
