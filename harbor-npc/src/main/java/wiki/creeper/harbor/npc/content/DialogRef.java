package wiki.creeper.harbor.npc.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.npc.api.DialogArgs;

/**
 * 대화 조건·액션 참조 한 건.
 *
 * <pre>{@code
 * - close                                   # 인자 없음
 * - goto: thanks                            # 값 하나 → value
 * - {type: affinity:add, value: 10, once: hansol_intro}
 * - flag:set: {flag: met_hansol, scope: perm}
 * }</pre>
 *
 * @param key 등록 키 ({@code 모듈:이름} 또는 NPC 기본 키)
 */
public record DialogRef(String key, DialogArgs args, SourcePos pos) {

    public static final Pattern KEY = Pattern.compile("[a-z0-9_]+(:[a-z0-9_]+)?");

    /** 노드를 가리키는 인자 이름. 콘텐츠 검사가 노드 존재와 도달 가능성을 확인한다. */
    public static final List<String> NODE_ARGS =
            List.of("node", "fail_node", "success_node", "after_node");

    /** 키의 모듈 부분 ({@code quest:accept} → quest, 기본 키는 빈 문자열). */
    public String namespace() {
        int colon = key.indexOf(':');
        return colon < 0 ? "" : key.substring(0, colon);
    }

    /** 이 참조가 가리키는 노드 ID들 (goto의 value 포함). */
    public List<String> nodeRefs() {
        List<String> nodes = new ArrayList<>();
        for (String arg : NODE_ARGS) {
            args.string(arg).ifPresent(nodes::add);
        }
        if (key.equals("goto")) {
            args.value().ifPresent(nodes::add);
        }
        return nodes;
    }

    public static List<DialogRef> parseList(YamlNode list) {
        List<DialogRef> refs = new ArrayList<>();
        for (YamlNode item : list.asList()) {
            parse(item).ifPresent(refs::add);
        }
        return List.copyOf(refs);
    }

    static Optional<DialogRef> parse(YamlNode item) {
        if (item.isScalar()) {
            return of(item, item.asString(), Map.of());
        }
        if (!item.isMap()) {
            item.error("조건·액션은 '키', {키: 값} 또는 {type: 키, ...} 형식이어야 합니다");
            return Optional.empty();
        }
        Map<String, YamlNode> entries = item.entries();
        if (entries.containsKey("type")) {
            Map<String, Object> args = new LinkedHashMap<>();
            entries.forEach(
                    (k, v) -> {
                        if (!k.equals("type") && !v.isNull()) {
                            args.put(k, v.toPlain());
                        }
                    });
            return of(item, entries.get("type").asString(), args);
        }
        if (entries.size() != 1) {
            item.error("조건·액션 하나에 키가 하나여야 합니다 (여러 인자는 {type: 키, 인자: 값})");
            return Optional.empty();
        }
        Map.Entry<String, YamlNode> only = entries.entrySet().iterator().next();
        YamlNode value = only.getValue();
        Map<String, Object> args = new LinkedHashMap<>();
        if (value.isMap()) {
            value.entries()
                    .forEach(
                            (k, v) -> {
                                if (!v.isNull()) {
                                    args.put(k, v.toPlain());
                                }
                            });
        } else if (!value.isNull()) {
            args.put("value", value.toPlain());
        }
        return of(item, only.getKey(), args);
    }

    private static Optional<DialogRef> of(YamlNode item, String key, Map<String, Object> args) {
        if (!KEY.matcher(key).matches()) {
            item.error("조건·액션 키 형식이 틀렸습니다 (소문자, 예: quest:accept): '" + key + "'");
            return Optional.empty();
        }
        return Optional.of(new DialogRef(key, DialogArgs.of(args), item.pos()));
    }
}
