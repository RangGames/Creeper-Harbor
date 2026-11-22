package wiki.creeper.harbor.npc.api;

import java.util.Locale;
import java.util.Optional;

/**
 * How a dialog is presented (proposed). Decided in the order dialogs/*.yml node {@code style:} →
 * npcs.yml {@code dialog_style:} → balance.yml {@code npc.dialog.default_style}.
 *
 * <ul>
 *   <li>{@link #DIALOG}: Dialog screen (default, blocks the screen)
 *   <li>{@link #HOLOGRAM}: speech bubble above the NPC's head (visible only to that player).
 *       Choices are chat buttons
 *   <li>{@link #TITLE}: title = speaker, subtitle = line. Choices are chat buttons
 *   <li>{@link #CHAT}: chat lines + chat buttons
 *   <li>{@link #ACTIONBAR}: action bar lines + chat buttons
 * </ul>
 *
 * Styles other than DIALOG do not block the screen, so right-clicking the same NPC again advances
 * to the next page.
 */
public enum DialogStyle {
    DIALOG,
    HOLOGRAM,
    TITLE,
    CHAT,
    ACTIONBAR;

    /** YAML key (lowercase). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this style blocks the screen (Dialog screen). */
    public boolean modal() {
        return this == DIALOG;
    }

    public static Optional<DialogStyle> parse(String key) {
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (DialogStyle s : values()) {
            if (s.key().equals(k)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** List for error messages ("dialog, hologram, …"). */
    public static String keys() {
        StringBuilder b = new StringBuilder();
        for (DialogStyle s : values()) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(s.key());
        }
        return b.toString();
    }
}
