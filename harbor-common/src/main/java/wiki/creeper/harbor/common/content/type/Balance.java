package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * balance.yml. Reads every number from '7. Balance design' by dot-joined path (e.g. {@code
 * fishing.legend.pity_start}). Reloads without a server restart.
 */
public final class Balance {

    public static final ContentType.Single<Balance> TYPE =
            new ContentType.Single<>("balance", "balance.yml", true, Balance::parse);

    private final Map<String, Object> values;

    private Balance(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public static Balance of(Map<String, Object> flatValues) {
        return new Balance(flatValues);
    }

    public Optional<String> string(String path) {
        Object value = values.get(path);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public double number(String path, double fallback) {
        return string(path).flatMap(Balance::parseDouble).orElse(fallback);
    }

    public int integer(String path, int fallback) {
        return (int) Math.round(number(path, fallback));
    }

    public boolean bool(String path, boolean fallback) {
        return string(path).map(Boolean::parseBoolean).orElse(fallback);
    }

    /** Keys directly under a path. {@code section("stats")} → rare_weight, bite_wait ... */
    public List<String> keys(String section) {
        String prefix = section + ".";
        TreeSet<String> keys = new TreeSet<>();
        for (String path : values.keySet()) {
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                int dot = rest.indexOf('.');
                keys.add(dot < 0 ? rest : rest.substring(0, dot));
            }
        }
        return List.copyOf(keys);
    }

    public int size() {
        return values.size();
    }

    static Balance parse(YamlNode node, ParseContext ctx) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", node, flat);
        return new Balance(flat);
    }

    private static void flatten(String prefix, YamlNode node, Map<String, Object> out) {
        if (node.isMap()) {
            node.entries()
                    .forEach((k, v) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, v, out));
        } else if (node.isList()) {
            out.put(prefix, node.toPlain());
        } else if (!node.isNull()) {
            out.put(prefix, node.asString());
        }
    }

    private static Optional<Double> parseDouble(String raw) {
        try {
            return Optional.of(Double.parseDouble(raw.replace("_", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
