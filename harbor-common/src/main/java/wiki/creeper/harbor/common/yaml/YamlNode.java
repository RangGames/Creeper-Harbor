package wiki.creeper.harbor.common.yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.TypedId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.common.util.ValidationErrors;

/**
 * 줄 번호를 기억하는 YAML 노드. SnakeYAML 노드 단위로 읽어서 오류 위치("42행 3열")를 알려준다.
 *
 * <p>값을 꺼내는 메서드는 오류가 나도 예외를 던지지 않고 오류 목록에 기록한 뒤 기본값을 돌려준다. 파일 하나를 끝까지 읽어서 오류를 모두 모은
 * 다음, 오류가 하나라도 있으면 결과를 버리는 방식으로 쓴다.
 */
public final class YamlNode {

    private final String file;
    private final Node node;
    private final ValidationErrors errors;
    private @Nullable Map<String, YamlNode> mapCache;

    private YamlNode(String file, Node node, ValidationErrors errors) {
        this.file = file;
        this.node = node;
        this.errors = errors;
    }

    // ------------------------------------------------------------------ 읽기

    /** 파일을 읽는다. 문법 오류나 빈 파일이면 오류를 기록하고 빈 Optional. */
    public static Optional<YamlNode> read(Path path, String displayName, ValidationErrors errors) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(displayName, reader, errors);
        } catch (IOException e) {
            errors.add(new SourcePos(displayName, 1, 1), "파일을 읽지 못했습니다: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<YamlNode> parse(String displayName, Reader reader, ValidationErrors errors) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Node root = new Yaml(new SafeConstructor(options)).compose(reader);
            if (root == null) {
                errors.add(new SourcePos(displayName, 1, 1), "빈 파일입니다");
                return Optional.empty();
            }
            return Optional.of(new YamlNode(displayName, root, errors));
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            SourcePos pos =
                    mark == null
                            ? new SourcePos(displayName, 1, 1)
                            : new SourcePos(displayName, mark.getLine() + 1, mark.getColumn() + 1);
            errors.add(pos, "YAML 문법 오류: " + e.getProblem());
            return Optional.empty();
        } catch (RuntimeException e) {
            errors.add(new SourcePos(displayName, 1, 1), "YAML을 읽지 못했습니다: " + e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 기본 정보

    public SourcePos pos() {
        Mark mark = node.getStartMark();
        return mark == null
                ? new SourcePos(file, 1, 1)
                : new SourcePos(file, mark.getLine() + 1, mark.getColumn() + 1);
    }

    public String file() {
        return file;
    }

    public ValidationErrors errors() {
        return errors;
    }

    public boolean isMap() {
        return node instanceof MappingNode;
    }

    public boolean isList() {
        return node instanceof SequenceNode;
    }

    public boolean isScalar() {
        return node instanceof ScalarNode;
    }

    /** null·~·빈 값인지. */
    public boolean isNull() {
        return node instanceof ScalarNode scalar
                && (scalar.getValue().isEmpty()
                        || scalar.getValue().equals("~")
                        || scalar.getValue().equals("null"));
    }

    /** 이 노드 위치에 오류를 기록한다. */
    public void error(String message) {
        errors.add(pos(), message);
    }

    // ------------------------------------------------------------------ 맵

    /** 맵의 항목 (입력 순서 유지). 맵이 아니면 오류를 기록하고 빈 맵. */
    public Map<String, YamlNode> entries() {
        if (mapCache != null) {
            return mapCache;
        }
        Map<String, YamlNode> result = new LinkedHashMap<>();
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
                    child(tuple.getKeyNode()).error("키는 문자열이어야 합니다");
                    continue;
                }
                if (result.containsKey(keyNode.getValue())) {
                    child(keyNode).error("키 '" + keyNode.getValue() + "'가 중복됩니다");
                    continue;
                }
                result.put(keyNode.getValue(), child(tuple.getValueNode()));
            }
        } else if (!isNull()) {
            error("맵(키: 값) 형식이어야 합니다");
        }
        mapCache = Collections.unmodifiableMap(result);
        return mapCache;
    }

    public Optional<YamlNode> get(String key) {
        YamlNode value = entries().get(key);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }

    public boolean has(String key) {
        return get(key).isPresent();
    }

    /** 필수 필드. 없으면 오류를 기록하고 빈 Optional. */
    public Optional<YamlNode> require(String key) {
        Optional<YamlNode> value = get(key);
        if (value.isEmpty()) {
            error("필수 필드 '" + key + "'가 없습니다");
        }
        return value;
    }

    /** 허용된 키 외의 키가 있으면 오류 (오타 방지). */
    public void allowOnly(Set<String> allowed) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && !allowed.contains(key.getValue())) {
                    child(key).error("알 수 없는 필드 '" + key.getValue() + "'");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 스칼라 (이 노드)

    public String asString() {
        if (node instanceof ScalarNode scalar) {
            return scalar.getValue();
        }
        error("문자열이어야 합니다");
        return "";
    }

    public long asLong() {
        String raw = asString().replace("_", "");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            error("정수여야 합니다: '" + raw + "'");
            return 0;
        }
    }

    public int asInt() {
        long value = asLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            error("정수 범위를 벗어났습니다: " + value);
            return 0;
        }
        return (int) value;
    }

    public double asDouble() {
        String raw = asString().replace("_", "");
        try {
            double value = Double.parseDouble(raw);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                error("유한한 숫자여야 합니다: '" + raw + "'");
                return 0;
            }
            return value;
        } catch (NumberFormatException e) {
            error("숫자여야 합니다: '" + raw + "'");
            return 0;
        }
    }

    public boolean asBoolean() {
        String raw = asString();
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                error("true 또는 false여야 합니다: '" + raw + "'");
                yield false;
            }
        };
    }

    public <E extends Enum<E> & Keyed> Optional<E> asEnum(Class<E> type) {
        String raw = asString().toLowerCase(Locale.ROOT);
        Optional<E> value = Keyed.fromKey(type, raw);
        if (value.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                keys.add(constant.key());
            }
            error("'" + raw + "'는 허용되지 않습니다. 가능한 값: " + String.join(", ", keys));
        }
        return value;
    }

    public Optional<HarborId> asId() {
        String raw = asString();
        Optional<HarborId> id = HarborId.tryParse(raw);
        if (id.isEmpty()) {
            error("ID 형식이 틀렸습니다 (소문자 영문·숫자·밑줄, 예: fish/harbor_mackerel): '" + raw + "'");
        }
        return id;
    }

    /** 접두사가 정해진 ID (예: {@code region/}). */
    public Optional<HarborId> asId(String prefix) {
        return asId()
                .filter(
                        id -> {
                            try {
                                TypedId.requirePrefix(id, prefix);
                                return true;
                            } catch (IllegalArgumentException e) {
                                error(e.getMessage());
                                return false;
                            }
                        });
    }

    public List<YamlNode> asList() {
        if (node instanceof SequenceNode sequence) {
            List<YamlNode> result = new ArrayList<>(sequence.getValue().size());
            for (Node item : sequence.getValue()) {
                result.add(child(item));
            }
            return result;
        }
        if (!isNull()) {
            error("목록 형식이어야 합니다");
        }
        return List.of();
    }

    /** 문자열 하나 또는 문자열 목록을 목록으로 읽는다 (설명 문구 등). */
    public List<String> asStringOrList() {
        if (isList()) {
            return asList().stream().map(YamlNode::asString).toList();
        }
        return List.of(asString());
    }

    /** 일반 Java 값으로 바꾼다 (Map, List, String). 밸런스 표처럼 형식을 모르는 데이터용. */
    public Object toPlain() {
        if (node instanceof MappingNode) {
            Map<String, Object> map = new LinkedHashMap<>();
            entries().forEach((k, v) -> map.put(k, v.toPlain()));
            return map;
        }
        if (node instanceof SequenceNode) {
            return asList().stream().map(YamlNode::toPlain).toList();
        }
        return asString();
    }

    // ------------------------------------------------------------------ 필드 접근 도우미

    public String string(String key) {
        return require(key).map(YamlNode::asString).orElse("");
    }

    public String string(String key, String fallback) {
        return get(key).map(YamlNode::asString).orElse(fallback);
    }

    public Optional<String> optString(String key) {
        return get(key).map(YamlNode::asString);
    }

    public int integer(String key) {
        return require(key).map(YamlNode::asInt).orElse(0);
    }

    public int integer(String key, int fallback) {
        return get(key).map(YamlNode::asInt).orElse(fallback);
    }

    /** 범위 검사를 포함한 정수. */
    public int integer(String key, int fallback, int min, int max) {
        Optional<YamlNode> value = get(key);
        if (value.isEmpty()) {
            return fallback;
        }
        int result = value.get().asInt();
        if (result < min || result > max) {
            value.get().error("'" + key + "'는 " + min + "~" + max + " 사이여야 합니다: " + result);
        }
        return result;
    }

    public long longValue(String key, long fallback) {
        return get(key).map(YamlNode::asLong).orElse(fallback);
    }

    public double number(String key) {
        return require(key).map(YamlNode::asDouble).orElse(0.0);
    }

    public double number(String key, double fallback) {
        return get(key).map(YamlNode::asDouble).orElse(fallback);
    }

    public boolean bool(String key) {
        return require(key).map(YamlNode::asBoolean).orElse(false);
    }

    public boolean bool(String key, boolean fallback) {
        return get(key).map(YamlNode::asBoolean).orElse(fallback);
    }

    public <E extends Enum<E> & Keyed> Optional<E> enumValue(String key, Class<E> type) {
        return require(key).flatMap(v -> v.asEnum(type));
    }

    public List<YamlNode> list(String key) {
        return get(key).map(YamlNode::asList).orElse(List.of());
    }

    public List<String> strings(String key) {
        return list(key).stream().map(YamlNode::asString).toList();
    }

    // ------------------------------------------------------------------ 내부

    private YamlNode child(Node child) {
        return new YamlNode(file, child, errors);
    }
}
