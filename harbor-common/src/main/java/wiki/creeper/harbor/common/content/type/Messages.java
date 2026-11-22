package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * messages_ko.yml. All output text (MiniMessage format). Code never writes text directly; it looks
 * it up by key.
 *
 * <p>Nested maps become dot-joined keys, and string lists are joined with newlines.
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
            node.entries()
                    .forEach((k, v) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, v, out));
        } else if (node.isList()) {
            out.put(prefix, String.join("\n", node.asStringOrList()));
        } else if (!node.isNull()) {
            out.put(prefix, node.asString());
        }
    }
}
