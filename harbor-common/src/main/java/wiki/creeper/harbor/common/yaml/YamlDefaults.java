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
 * 설정 파일에 새로 생긴 항목 채우기 (추가 제안). 서버의 YAML에 없는 키를 기본 YAML에서 찾아, 그 키의 줄(바로 위 주석 포함)을 같은 상위 섹션의 끝에 끼워
 * 넣는다. 파일을 다시 쓰지 않고 줄만 더하므로 서버에서 고친 값·주석·순서는 그대로다.
 *
 * <p>블록 형식 맵(키: 값, 들여쓰기로 하위 키)만 따라 들어간다. 목록·여러 줄 문자열·한 줄 맵({a: 1}) 값은 통째로 한 항목으로 본다 (안을 섞지 않는다).
 * 최상위가 목록인 파일(어종·아이템 목록 등)은 건드리지 않는다.
 */
public final class YamlDefaults {

    private static final Pattern KEY =
            Pattern.compile(
                    "^(\\s*)(\"[^\"]*\"|'[^']*'|[^\\s#\"'-][^:#]*?|-[^\\s:#][^:#]*?)\\s*:(\\s.*|)$");

    /**
     * @param text 합친 내용 (바뀌지 않았으면 원래 내용)
     * @param added 더한 키 경로 (점으로 이음)
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
     * 설정 파일에 기본값의 빠진 키를 모두 채운다 (harbor-infra.yml · harbor-proxy.yml 등). 바뀌면 원래 파일을 {@code
     * <이름>.bak}으로 남기고 쓴다.
     *
     * @param defaults jar의 기본 파일 (null이면 아무것도 하지 않음)
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

    /** 기본 YAML의 모든 키 경로 (블록 맵 안쪽까지, 점으로 이음). */
    public static Set<String> keys(String yaml) {
        Set<String> out = new LinkedHashSet<>();
        collect(parse(lines(yaml)), "", out);
        return out;
    }

    /**
     * @param user 서버의 파일 내용
     * @param defaults jar의 기본 내용
     * @param shouldAdd 이 키 경로를 더할지 (예: 지난 기본값에 없던 새 키만)
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
        // 끼울 자리(사용자 파일 줄 번호) → 끼울 줄들 (기본값 순서대로)
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

    /** 이 섹션의 마지막 내용 줄 (뒤따르는 빈 줄 제외). 루트면 파일 끝. */
    private static int lastContentLine(Node node, List<String> lines) {
        int last = node.indent < 0 ? lines.size() - 1 : node.end - 1;
        while (last > node.line && last >= 0 && isBlank(lines.get(last))) {
            last--;
        }
        return last;
    }

    /** 키 바로 위에 붙은 주석 줄의 시작. */
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

    // ------------------------------------------------------------------ 줄 구조

    /** 맵 키 하나. line = 키 줄, end = 이 키 블록 다음 줄. */
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
            return root; // 최상위가 목록인 파일
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

    /** from~to 줄에서 parent 바로 아래 키들을 읽는다. */
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

    /** 들여쓰기가 indent보다 깊은 줄이 끝나는 곳 (빈 줄·주석은 다음 내용 줄을 따른다). */
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

    /** 값 뒤 주석 (따옴표 밖의 " #") 을 뗀다. */
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
