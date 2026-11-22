package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Line text helpers (no server API): truncating to a character count for the typewriter effect
 * (keeping decorations), counting characters, and auto-advance calculation. Characters are counted
 * by code point (one Hangul syllable = 1).
 */
public final class SpeechText {

    private SpeechText() {}

    /** Number of visible characters (excluding decoration tags). */
    public static int length(Component text) {
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        return plain.codePointCount(0, plain.length());
    }

    /**
     * Keeps only the first {@code chars} characters. Decorations like color and bold are kept, and
     * non-text pieces (translations, key binds) count as one character each.
     */
    public static Component slice(Component text, int chars) {
        if (chars <= 0) {
            return Component.empty();
        }
        int[] budget = {chars};
        return sliceInto(text, budget);
    }

    private static Component sliceInto(Component c, int[] budget) {
        Component head;
        if (c instanceof TextComponent t) {
            String content = t.content();
            int cps = content.codePointCount(0, content.length());
            if (cps <= budget[0]) {
                budget[0] -= cps;
                head = t.children(List.of());
            } else {
                int end = content.offsetByCodePoints(0, budget[0]);
                budget[0] = 0;
                return t.content(content.substring(0, end)).children(List.of());
            }
        } else {
            budget[0] -= 1;
            head = c.children(List.of());
        }
        List<Component> kids = new ArrayList<>();
        for (Component child : c.children()) {
            if (budget[0] <= 0) {
                break;
            }
            kids.add(sliceInto(child, budget));
        }
        return head.children(kids);
    }

    /**
     * Number of typewriter steps: how many ticks it takes to show all characters at {@code perTick}
     * per tick. 0 if perTick is 0 (all at once).
     */
    public static int typewriterTicks(int length, int perTick) {
        if (perTick <= 0 || length <= 0) {
            return 0;
        }
        return (length + perTick - 1) / perTick;
    }

    /** Auto-advance ticks (seconds to ticks, 0 if 0 or less). */
    public static long autoTicks(double seconds) {
        return seconds <= 0 ? 0 : Math.max(1, Math.round(seconds * 20));
    }
}
