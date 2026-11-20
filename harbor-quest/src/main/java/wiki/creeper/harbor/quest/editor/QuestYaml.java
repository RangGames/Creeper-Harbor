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
 * 퀘스트 편집기의 YAML 읽기·쓰기 (서버 API 없음, 단위 테스트 대상). 파일 전체를 다시 쓰지 않고 그 퀘스트 항목 블록만 바꿔서 다른 항목의 주석·서식을 지킨다.
 * 항목 안의 주석은 편집기로 저장하면 사라진다.
 *
 * <p>쓰는 모양은 손으로 쓴 콘텐츠와 같다: 위쪽 키는 블록, 목표·아이템 목록은 한 줄 {...}, 설명은 블록 목록.
 */
public final class QuestYaml {

    /** 쓰는 키 순서 (나머지 키는 원래 순서로 뒤에). */
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

    // ------------------------------------------------------------------ 읽기

    /** 파일 글에서 그 ID 항목을 일반 Java 값(Map·List·String)으로. 없거나 YAML이 깨졌으면 비어 있음. */
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

    /** "harbor:quest/x"와 "quest/x"를 같은 ID로 본다. */
    static boolean sameId(String a, String b) {
        return strip(a).equals(strip(b));
    }

    private static String strip(String id) {
        String s = id.strip();
        return s.startsWith("harbor:") ? s.substring("harbor:".length()) : s;
    }

    // ------------------------------------------------------------------ 블록 교체

    /**
     * 파일 글에서 그 항목 블록을 새 글로 바꾼다. 없으면 끝에 붙인다.
     *
     * @param entryText {@link #emit}으로 만든 항목 글 ("- id: …"로 시작, 줄바꿈으로 끝남)
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

    /** 항목 블록을 지운다. 없으면 그대로. */
    public static String remove(String fileText, String id) {
        String nl = fileText.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines =
                new ArrayList<>(List.of(fileText.replace("\r\n", "\n").split("\n", -1)));
        int[] range = block(lines, id);
        if (range == null) {
            return fileText;
        }
        int end = range[1];
        // 뒤따르는 빈 줄 하나도 지운다
        if (end < lines.size() && lines.get(end).isBlank() && end + 1 < lines.size()) {
            end++;
        }
        for (int i = end - 1; i >= range[0]; i--) {
            lines.remove(i);
        }
        return String.join(nl, lines);
    }

    /** 파일에 그 ID 항목이 있는지 (블록 시작 줄 기준). */
    public static boolean contains(String fileText, String id) {
        return block(List.of(fileText.replace("\r\n", "\n").split("\n", -1)), id) != null;
    }

    /**
     * [시작 줄, 끝 줄(제외)]. 시작은 {@code - id: <ID>} 또는 {@code - {id: <ID>, …}}, 끝은 다음 맨 앞 "- " 줄이나 파일
     * 끝이고, 끝 앞의 빈 줄·주석 줄(다음 항목 머리 주석)은 블록에서 뺀다.
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

    // ------------------------------------------------------------------ 쓰기

    /** 항목 하나를 글로 ("- id: …" 블록). */
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

    /** {@code key: value}. 지도는 아래 블록, 지도 목록은 줄마다 한 줄 {...}, 짧은 값 목록은 [..], 긴 글 목록은 블록. */
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

    /** 한 줄 모양 ({a: 1, b: [x, y]}). */
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

    /** 값 하나. 숫자·ID·한 단어는 그대로, 나머지(공백·콜론 뒤 공백·특수 문자·예약어)는 큰따옴표. */
    static String scalar(Object value, boolean inFlow) {
        String s = String.valueOf(value);
        if (value instanceof Number || value instanceof Boolean) {
            return s;
        }
        if (NUMBER.matcher(s).matches()) {
            return s; // 숫자처럼 보이는 글은 로더가 어느 쪽으로도 읽는다 (asString / asInt)
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
