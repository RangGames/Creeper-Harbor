package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * messages_ko.yml. 모든 출력 문구 (MiniMessage 형식). 코드에는 문구를 직접 쓰지 않고 키로 찾는다.
 *
 * <p>중첩 맵은 점으로 이은 키가 되고, 문자열 목록은 줄바꿈으로 합친다.
 */
public final class Messages {

    public static final ContentType.Single<Messages> TYPE =
            new ContentType.Single<>("messages", "messages_ko.yml", true, Messages::parse);

    private final Map<String, String> values;

    private Messages(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Messages of(Map<String, String> values) {
        return new Messages(values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Set<String> keys() {
        return values.keySet();
    }

    static Messages parse(YamlNode node, ParseContext ctx) {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", node, flat);
        return new Messages(flat);
    }

    private static void flatten(String prefix, YamlNode node, Map<String, String> out) {
        if (node.isMap()) {
            node.entries().forEach((k, v) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, v, out));
        } else if (node.isList()) {
            out.put(prefix, String.join("\n", node.asStringOrList()));
        } else if (!node.isNull()) {
            out.put(prefix, node.asString());
        }
    }
}
