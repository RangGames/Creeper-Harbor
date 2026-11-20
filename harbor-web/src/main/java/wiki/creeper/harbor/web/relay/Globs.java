package wiki.creeper.harbor.web.relay;

import java.util.Collection;
import java.util.regex.Pattern;

/** type 고르기용 glob ({@code *}는 아무 글자, 나머지는 그대로). 서버 API 없음. */
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
