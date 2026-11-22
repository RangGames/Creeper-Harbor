package wiki.creeper.harbor.quest.editor;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * YAML read/write for the quest editor (no server API, unit tested). Instead of rewriting the whole
 * file it replaces only that quest's entry block, preserving other entries' comments and
 * formatting. Comments inside the entry are lost when saved by the editor.
 *
 * <p>The output looks like hand-written content: top-level keys as blocks, objective and item lists
 * as one-line {...}, the description as a block list.
 */
public final class QuestYaml {

    /** Key order to write (the remaining keys follow in their original order). */
    static final List<String> ORDER =
            List.of(
                    "id",
                    "name",
                    "type",
                    "chapter",
                    "description",
                    "requires",
                    "pool",
                    "start_npc",
                    "end_npc",
                    "offer_style",
                    "cutscene",
                    "cutscene_at",
                    "objectives_mode",
                    "objectives",
                    "rewards",
                    "repeat",
                    "donate_delivered",
                    "abandonable",
                    "time_window");

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:+\\-가-힣ㄱ-ㅎㅏ-ㅣ]+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final List<String> RESERVED =
            List.of("true", "false", "yes", "no", "on", "off", "null", "~");

    private QuestYaml() {}

    // ------------------------------------------------------------------ Reading

    /**
     * The entry with that ID from the file text, as plain Java values (Map, List, String). Empty if
     * absent or the YAML is broken.
     */
    public static Optional<Map<String, Object>> read(String fileText, String id) {
        ValidationErrors errors = new ValidationErrors();
        Optional<YamlNode> root = YamlNode.parse("editor", new StringReader(fileText), errors);
        if (root.isEmpty() || !root.get().isList()) {
            return Optional.empty();
        }
        for (YamlNode entry : root.get().asList()) {
            if (entry.isMap() && sameId(entry.string("id", ""), id)) {
                Object plain = entry.toPlain();
                if (plain instanceof Map<?, ?> map) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    map.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return Optional.of(out);
                }
            }
        }
        return Optional.empty();
    }

    /** Treats "harbor:quest/x" and "quest/x" as the same ID. */
    static boolean sameId(String a, String b) {
        return strip(a).equals(strip(b));
    }

    private static String strip(String id) {
        String s = id.strip();
        return s.startsWith("harbor:") ? s.substring("harbor:".length()) : s;
    }

    // ------------------------------------------------------------------ Block replacement

    /**
     * Replaces that entry block in the file text with the new text. Appends it at the end if
     * absent.
     *
     * @param entryText entry text built by {@link #emit} (starts with "- id: …", ends with a
     *     newline)
     */
    public static String replace(String fileText, String id, String entryText) {
        String nl = fileText.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines =
                new ArrayList<>(List.of(fileText.replace("\r\n", "\n").split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        List<String> entry = List.of(entryText.replace("\r\n", "\n").stripTrailing().split("\n"));
        int[] range = block(lines, id);
        if (range == null) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) {
                lines.add("");
            }
            lines.addAll(entry);
        } else {
            for (int i = range[1] - 1; i >= range[0]; i--) {
                lines.remove(i);
            }
            lines.addAll(range[0], entry);
        }
        return String.join(nl, lines) + nl;
    }

    /** Removes the entry block. Unchanged if absent. */
    public static String remove(String fileText, String id) {
        String nl = fileText.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines =
                new ArrayList<>(List.of(fileText.replace("\r\n", "\n").split("\n", -1)));
        int[] range = block(lines, id);
        if (range == null) {
            return fileText;
        }
        int end = range[1];
        // also remove one following blank line
        if (end < lines.size() && lines.get(end).isBlank() && end + 1 < lines.size()) {
            end++;
        }
        for (int i = end - 1; i >= range[0]; i--) {
            lines.remove(i);
        }
        return String.join(nl, lines);
    }

    /** Whether the file has the entry with that ID (by block start line). */
    public static boolean contains(String fileText, String id) {
        return block(List.of(fileText.replace("\r\n", "\n").split("\n", -1)), id) != null;
    }

    /**
     * [start line, end line (exclusive)]. The start is {@code - id: <ID>} or {@code - {id: <ID>,
     * …}}, the end is the next "- " line at column 0 or the end of the file; blank and comment
     * lines before the end (the next entry's header comment) are excluded from the block.
     */
    static int @Nullable [] block(List<String> lines, String id) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (startsEntry(lines.get(i), id)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- ") || lines.get(i).equals("-")) {
                end = i;
                break;
            }
        }
        while (end > start + 1) {
            String prev = lines.get(end - 1).strip();
            if (prev.isEmpty() || prev.startsWith("#")) {
                end--;
            } else {
                break;
            }
        }
        return new int[] {start, end};
    }

    private static boolean startsEntry(String line, String id) {
        if (!line.startsWith("- ")) {
            return false;
        }
        String rest = line.substring(2).strip();
        if (rest.startsWith("{")) {
            rest = rest.substring(1).strip();
        }
        if (!rest.startsWith("id:")) {
            return false;
        }
        String value = rest.substring(3).strip();
        int cut = value.length();
        for (char c : new char[] {',', '}', '#', ' '}) {
            int at = value.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        value = value.substring(0, cut).replace("\"", "").replace("'", "");
        return sameId(value, id);
    }

    // ------------------------------------------------------------------ Writing

    /** One entry as text (a "- id: …" block). */
    public static String emit(Map<String, Object> entry) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String key : ordered(entry)) {
            Object value = entry.get(key);
            if (value == null || isEmpty(value)) {
                continue;
            }
            out.append(first ? "- " : "  ");
            first = false;
            writeKey(out, key, value, 2);
        }
        return out.toString();
    }

    private static List<String> ordered(Map<String, Object> entry) {
        List<String> keys = new ArrayList<>();
        for (String k : ORDER) {
            if (entry.containsKey(k)) {
                keys.add(k);
            }
        }
        for (String k : entry.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static boolean isEmpty(Object value) {
        return (value instanceof Map<?, ?> m && m.isEmpty())
                || (value instanceof List<?> l && l.isEmpty())
                || (value instanceof String s && s.isEmpty());
    }

    /**
     * {@code key: value}. Maps as a block below, lists of maps as one {...} per line, short value
     * lists as [..], long text lists as a block.
     */
    private static void writeKey(StringBuilder out, String key, Object value, int indent) {
        out.append(key).append(':');
        switch (value) {
            case Map<?, ?> map -> {
                if (flowable(map) && flow(map).length() <= 80) {
                    out.append(' ').append(flow(map)).append('\n');
                    return;
                }
                out.append('\n');
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() == null || isEmpty(e.getValue())) {
                        continue;
                    }
                    out.append(" ".repeat(indent + 2));
                    writeKey(out, String.valueOf(e.getKey()), e.getValue(), indent + 2);
                }
            }
            case List<?> list -> {
                boolean maps = list.stream().allMatch(v -> v instanceof Map<?, ?>);
                boolean shortScalars =
                        list.stream().allMatch(v -> v instanceof String s && s.length() <= 40)
                                && flow(list).length() <= 80;
                if (!maps && shortScalars) {
                    out.append(' ').append(flow(list)).append('\n');
                    return;
                }
                out.append('\n');
                for (Object v : list) {
                    out.append(" ".repeat(indent + 2)).append("- ");
                    out.append(
                            v instanceof Map<?, ?> || v instanceof List<?>
                                    ? flow(v)
                                    : scalar(v, false));
                    out.append('\n');
                }
            }
            default -> out.append(' ').append(scalar(value, false)).append('\n');
        }
    }

    private static boolean flowable(Map<?, ?> map) {
        return map.values().stream().noneMatch(v -> v instanceof Map<?, ?> m && !m.isEmpty());
    }

    /** One-line form ({a: 1, b: [x, y]}). */
    static String flow(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    if (!first) {
                        b.append(", ");
                    }
                    first = false;
                    b.append(e.getKey()).append(": ").append(flow(e.getValue()));
                }
                yield b.append('}').toString();
            }
            case List<?> list -> {
                StringBuilder b = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        b.append(", ");
                    }
                    b.append(flow(list.get(i)));
                }
                yield b.append(']').toString();
            }
            default -> scalar(value, true);
        };
    }

    /**
     * A single value. Numbers, IDs and single words as is; anything else (spaces, a space after a
     * colon, special characters, reserved words) in double quotes.
     */
    static String scalar(Object value, boolean inFlow) {
        String s = String.valueOf(value);
        if (value instanceof Number || value instanceof Boolean) {
            return s;
        }
        if (NUMBER.matcher(s).matches()) {
            return s; // text that looks like a number is read either way by the loader (asString /
            // asInt)
        }
        boolean plain =
                PLAIN.matcher(s).matches()
                        && !RESERVED.contains(s.toLowerCase(Locale.ROOT))
                        && !s.contains(": ")
                        && !s.endsWith(":")
                        && !s.startsWith("-")
                        && !(inFlow && (s.contains(",") || s.contains(":")));
        if (plain) {
            return s;
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
