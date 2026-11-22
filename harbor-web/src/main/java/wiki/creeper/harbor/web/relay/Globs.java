package wiki.creeper.harbor.web.relay;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Glob for type matching ({@code *} matches anything, everything else is literal). No server API.
 */
public final class Globs {

    private Globs() {}

    public static boolean matches(String glob, String value) {
        if (glob.equals("*")) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.matches(regex.toString(), value);
    }

    public static boolean any(Collection<String> globs, String value) {
        for (String g : globs) {
            if (matches(g, value)) {
                return true;
            }
        }
        return false;
    }
}
