package wiki.creeper.harbor.common.yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Fills in newly added entries in config files (proposed). Finds keys missing from the server's
 * YAML in the default YAML and inserts that key's lines (including the comments right above) at the
 * end of the same parent section. It only adds lines without rewriting the file, so values,
 * comments and order edited on the server stay as they are.
 *
 * <p>Only descends into block-style maps (key: value, child keys by indentation). Lists, multi-line
 * strings and flow maps ({a: 1}) are treated as a single entry (their contents are not mixed).
 * Files whose top level is a list (fish lists, item lists, etc.) are left untouched.
 */
public final class YamlDefaults {

    private static final Pattern KEY =
            Pattern.compile(
                    "^(\\s*)(\"[^\"]*\"|'[^']*'|[^\\s#\"'-][^:#]*?|-[^\\s:#][^:#]*?)\\s*:(\\s.*|)$");

    /**
     * @param text merged content (the original content if unchanged)
     * @param added added key paths (dot-joined)
     */
    public record Result(String text, List<String> added) {
        public Result {
            added = List.copyOf(added);
        }

        public boolean changed() {
            return !added.isEmpty();
        }
    }

    private YamlDefaults() {}

    /**
     * Fills all keys missing from a config file with defaults (harbor-infra.yml, harbor-proxy.yml,
     * etc.). If changed, keeps the original file as {@code <name>.bak} and writes.
     *
     * @param defaults default file from the jar (null means do nothing)
     */
    public static Result fillFile(Path file, @Nullable InputStream defaults) throws IOException {
        if (defaults == null || Files.notExists(file)) {
            return new Result("", List.of());
        }
        String base = new String(defaults.readAllBytes(), StandardCharsets.UTF_8);
        String current = Files.readString(file, StandardCharsets.UTF_8);
        Result result = fill(current, base, k -> true);
        if (result.changed()) {
            Path name = file.getFileName();
            Files.writeString(
                    file.resolveSibling((name == null ? "config" : name.toString()) + ".bak"),
                    current,
                    StandardCharsets.UTF_8);
            Files.writeString(file, result.text(), StandardCharsets.UTF_8);
        }
        return result;
    }

    /** All key paths of the default YAML (into block maps, dot-joined). */
    public static Set<String> keys(String yaml) {
        Set<String> out = new LinkedHashSet<>();
        collect(parse(lines(yaml)), "", out);
        return out;
    }

    /**
     * @param user the server's file content
     * @param defaults the default content from the jar
     * @param shouldAdd whether to add this key path (e.g. only keys new since the previous
     *     defaults)
     */
    public static Result fill(String user, String defaults, Predicate<String> shouldAdd) {
        String nl = user.contains("\r\n") ? "\r\n" : "\n";
        List<String> userLines = lines(user);
        List<String> defaultLines = lines(defaults);
        Node userRoot = parse(userLines);
        Node defaultRoot = parse(defaultLines);
        if (defaultRoot.children.isEmpty()) {
            return new Result(user, List.of());
        }
        // Insertion point (user file line number) → lines to insert (in default order)
        Map<Integer, List<String>> inserts = new TreeMap<>();
        List<String> added = new ArrayList<>();
        merge(defaultRoot, userRoot, "", defaultLines, userLines, shouldAdd, inserts, added);
        if (added.isEmpty()) {
            return new Result(user, List.of());
        }
        List<String> out = new ArrayList<>(userLines);
        List<Integer> at = new ArrayList<>(inserts.keySet());
        for (int i = at.size() - 1; i >= 0; i--) {
            int index = at.get(i);
            out.addAll(index, inserts.get(index));
        }
        String text = String.join(nl, out);
        if (user.isEmpty() || user.endsWith("\n")) {
            text += nl;
        }
        return new Result(text, added);
    }

    private static void merge(
            Node def,
            Node user,
            String prefix,
            List<String> defaultLines,
            List<String> userLines,
            Predicate<String> shouldAdd,
            Map<Integer, List<String>> inserts,
            List<String> added) {
        for (Node child : def.children.values()) {
            String path = prefix.isEmpty() ? child.key : prefix + "." + child.key;
            Node mine = user.children.get(child.key);
            if (mine != null) {
                if (!child.children.isEmpty() && !mine.children.isEmpty()) {
                    merge(child, mine, path, defaultLines, userLines, shouldAdd, inserts, added);
                }
                continue;
            }
            if (!user.isMapping() || !shouldAdd.test(path)) {
                continue;
            }
            int userIndent =
                    user.children.isEmpty()
                            ? (user.indent < 0 ? 0 : user.indent + 2)
                            : user.children.values().iterator().next().indent;
            int delta = userIndent - child.indent;
            int position = lastContentLine(user, userLines) + 1;
            List<String> block = inserts.computeIfAbsent(position, k -> new ArrayList<>());
            for (int i = commentStart(child, defaultLines); i < child.end; i++) {
                block.add(reindent(defaultLines.get(i), delta));
            }
            trimTrailingBlank(block);
            added.add(path);
        }
    }

    /**
     * Last content line of this section (excluding trailing blank lines). End of file for the root.
     */
    private static int lastContentLine(Node node, List<String> lines) {
        int last = node.indent < 0 ? lines.size() - 1 : node.end - 1;
        while (last > node.line && last >= 0 && isBlank(lines.get(last))) {
            last--;
        }
        return last;
    }

    /** Start of the comment lines attached right above a key. */
    private static int commentStart(Node node, List<String> lines) {
        int i = node.line;
        while (i - 1 >= 0 && lines.get(i - 1).strip().startsWith("#")) {
            i--;
        }
        return i;
    }

    private static void trimTrailingBlank(List<String> block) {
        while (!block.isEmpty() && isBlank(block.getLast())) {
            block.removeLast();
        }
    }

    private static String reindent(String line, int delta) {
        if (delta == 0 || isBlank(line)) {
            return line;
        }
        if (delta > 0) {
            return " ".repeat(delta) + line;
        }
        int strip = Math.min(-delta, indent(line));
        return line.substring(strip);
    }

    // ------------------------------------------------------------------ Line structure

    /** One map key. line = key line, end = line after this key's block. */
    private static final class Node {
        final String key;
        final int indent;
        final int line;
        int end;
        boolean mapping;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String key, int indent, int line) {
            this.key = key;
            this.indent = indent;
            this.line = line;
        }

        boolean isMapping() {
            return indent < 0 || mapping;
        }
    }

    private static Node parse(List<String> lines) {
        Node root = new Node("", -1, -1);
        root.end = lines.size();
        if (firstContentIsList(lines)) {
            return root; // file whose top level is a list
        }
        parseChildren(root, lines, 0, lines.size());
        return root;
    }

    private static boolean firstContentIsList(List<String> lines) {
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.equals("---")) {
                continue;
            }
            return s.startsWith("-") || s.startsWith("[");
        }
        return false;
    }

    /** Reads the keys directly under parent in lines from~to. */
    private static void parseChildren(Node parent, List<String> lines, int from, int to) {
        int i = from;
        int childIndent = -1;
        while (i < to) {
            String line = lines.get(i);
            if (isBlank(line) || line.strip().startsWith("#")) {
                i++;
                continue;
            }
            int indent = indent(line);
            if (indent <= parent.indent) {
                break;
            }
            Matcher m = KEY.matcher(line);
            if (!m.matches() || (childIndent >= 0 && indent != childIndent)) {
                i++;
                continue;
            }
            childIndent = indent;
            Node node = new Node(unquote(m.group(2).strip()), indent, i);
            int end = blockEnd(lines, i + 1, to, indent);
            node.end = end;
            String value = stripComment(m.group(3)).strip();
            if (value.isEmpty() && nextIsKey(lines, i + 1, end, indent)) {
                node.mapping = true;
                parseChildren(node, lines, i + 1, end);
            }
            parent.children.putIfAbsent(node.key, node);
            i = end;
        }
    }

    /**
     * Where lines indented deeper than indent end (blank/comment lines follow the next content
     * line).
     */
    private static int blockEnd(List<String> lines, int from, int to, int indent) {
        int end = from;
        int i = from;
        while (i < to) {
            String line = lines.get(i);
            if (isBlank(line) || line.strip().startsWith("#")) {
                i++;
                continue;
            }
            if (indent(line) <= indent) {
                break;
            }
            i++;
            end = i;
        }
        return end;
    }

    private static boolean nextIsKey(List<String> lines, int from, int to, int indent) {
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (isBlank(line) || line.strip().startsWith("#")) {
                continue;
            }
            return indent(line) > indent && KEY.matcher(line).matches();
        }
        return false;
    }

    private static void collect(Node node, String prefix, Set<String> out) {
        for (Node child : node.children.values()) {
            String path = prefix.isEmpty() ? child.key : prefix + "." + child.key;
            out.add(path);
            collect(child, path, out);
        }
    }

    private static List<String> lines(String text) {
        List<String> list = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!list.isEmpty() && list.getLast().isEmpty()) {
            list.removeLast();
        }
        return list;
    }

    private static boolean isBlank(String line) {
        return line.isBlank();
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String unquote(String key) {
        if (key.length() >= 2
                && ((key.startsWith("\"") && key.endsWith("\""))
                        || (key.startsWith("'") && key.endsWith("'")))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /** Strips a trailing comment (" #" outside quotes) after the value. */
    private static String stripComment(@Nullable String value) {
        if (value == null) {
            return "";
        }
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == '#' && !single && !dbl && (i == 0 || value.charAt(i - 1) == ' ')) {
                return value.substring(0, i);
            }
        }
        return value;
    }
}
