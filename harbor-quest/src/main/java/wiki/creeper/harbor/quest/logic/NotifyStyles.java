package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses a notification style list ("chat,title" → [CHAT, TITLE]). Unknown values are skipped;
 * "none" sends nothing. No server API.
 */
public final class NotifyStyles {

    /** Notification style. */
    public enum Style {
        CHAT,
        TITLE,
        ACTIONBAR
    }

    private static final Pattern SEPARATOR = Pattern.compile("[,\\s]+");

    private NotifyStyles() {}

    public static List<Style> parse(String raw) {
        List<Style> styles = new ArrayList<>();
        for (String part : SEPARATOR.splitAsStream(raw).toList()) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            Style style =
                    switch (p) {
                        case "chat" -> Style.CHAT;
                        case "title" -> Style.TITLE;
                        case "actionbar", "action_bar" -> Style.ACTIONBAR;
                        default -> null;
                    };
            if (style != null && !styles.contains(style)) {
                styles.add(style);
            }
        }
        return List.copyOf(styles);
    }
}
