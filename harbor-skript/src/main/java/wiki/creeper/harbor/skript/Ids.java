package wiki.creeper.harbor.skript;

import java.util.Locale;
import java.util.Optional;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * ID text written by a script → Harbor ID (no server API). Accepts both {@code quest/main/x} and
 * {@code harbor:quest/main/x}, and adds the leading kind if omitted ({@code main/x} + prefix quest/
 * → quest/main/x). Empty if the format is wrong.
 */
public final class Ids {

    private Ids() {}

    public static Optional<HarborId> parse(String raw, String prefix) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        String path = s.startsWith("harbor:") ? s.substring("harbor:".length()) : s;
        if (!path.startsWith(prefix)) {
            path = prefix + path;
        }
        return HarborId.tryParse(path).filter(i -> i.hasPrefix(prefix));
    }

    /** Flag name (lowercase letters, digits, underscores, separated by /). */
    public static boolean flag(String raw) {
        return wiki.creeper.harbor.core.api.FlagService.NAME.matcher(raw).matches();
    }
}
