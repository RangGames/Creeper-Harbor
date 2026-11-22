package wiki.creeper.harbor.skript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parsing of script argument text (no server API, unit tested): {@code key=value} pairs for
 * placeholders and external event data, script modifier sources and sound keys.
 */
public final class ScriptArgs {

    private ScriptArgs() {}

    /** MiniMessage tag name rule for placeholder keys. */
    private static final Pattern TAG = Pattern.compile("[a-z0-9_\\-]+");

    /** Prefix forced on modifier sources written by scripts. */
    public static final String SOURCE_PREFIX = "skript:";

    /**
     * {@code key=value} strings → ordered map. Entries without {@code =} or with an invalid key
     * (lowercase letters, digits, underscore, hyphen) are skipped; the value keeps everything after
     * the first {@code =}. A later duplicate key wins.
     */
    public static Map<String, String> pairs(List<String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String entry : raw) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            if (TAG.matcher(key).matches()) {
                out.put(key, entry.substring(eq + 1));
            }
        }
        return out;
    }

    /**
     * Value typing for external event data: integers → Long, decimals → Double, true/false →
     * Boolean, anything else stays text.
     */
    public static Object typed(String value) {
        String s = value.strip();
        if (s.equals("true") || s.equals("false")) {
            return Boolean.parseBoolean(s);
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            // not an integer
        }
        try {
            double d = Double.parseDouble(s);
            if (!Double.isNaN(d) && !Double.isInfinite(d) && !s.isEmpty()) {
                return d;
            }
        } catch (NumberFormatException e) {
            // not a number
        }
        return value;
    }

    /**
     * Modifier source written by a script, always under {@code skript:} so scripts can't remove
     * skill or event modifiers. Empty if blank.
     */
    public static Optional<String> source(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(s.startsWith(SOURCE_PREFIX) ? s : SOURCE_PREFIX + s);
    }

    /** Sound key: a key without a namespace is a Harbor sound event ({@code harbor:<key>}). */
    public static String soundKey(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        return s.indexOf(':') >= 0 ? s : "harbor:" + s;
    }
}
