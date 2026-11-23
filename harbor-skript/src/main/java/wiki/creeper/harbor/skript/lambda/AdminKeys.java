package wiki.creeper.harbor.skript.lambda;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Which syntax elements are audited, and their admin_log action keys ({@link AdminAudit}). */
final class AdminKeys {

    private static final String ADMIN_PREFIX = "harbor admin ";
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    private AdminKeys() {}

    /** Whether any pattern is an admin pattern ({@code harbor admin ...}). */
    static boolean isAdmin(String... patterns) {
        for (String p : patterns) {
            if (p.strip().toLowerCase(Locale.ROOT).startsWith(ADMIN_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stable action key: {@code skript.} + the registration label without the words {@code harbor}
     * and {@code admin}, joined by underscores ({@code "harbor admin complete quest"} → {@code
     * skript.complete_quest}).
     */
    static String action(String label) {
        List<String> words = new ArrayList<>();
        for (String w : NON_WORD.split(label.toLowerCase(Locale.ROOT), -1)) {
            if (!w.isEmpty() && !w.equals("harbor") && !w.equals("admin")) {
                words.add(w);
            }
        }
        return "skript." + String.join("_", words);
    }
}
