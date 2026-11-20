package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 알림 형태 목록 파싱 ("chat,title" → [CHAT, TITLE]). 모르는 값은 건너뛰고, "none"이면 아무것도 보내지 않는다. 서버 API 없음. */
public final class NotifyStyles {

    /** 알림 형태. */
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
