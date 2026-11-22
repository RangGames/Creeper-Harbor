package wiki.creeper.harbor.npc.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Arguments of a dialog condition or action (raw YAML values: strings, lists, maps). Uses no server
 * API, so harbor-tools validates with the same class.
 *
 * <pre>{@code
 * # dialogs/*.yml
 * actions:
 *   - {type: affinity:add, value: 10, once: hansol_intro}
 *   - quest:accept: harbor:quest/main/ch1_dark_lighthouse   # a single value is the "value" argument
 * }</pre>
 */
public record DialogArgs(Map<String, Object> values) {

    public static final DialogArgs EMPTY = new DialogArgs(Map.of());

    public DialogArgs {
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static DialogArgs of(Map<String, ?> values) {
        return new DialogArgs(new LinkedHashMap<>(values));
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    /** String value (empty for lists and maps). */
    public Optional<String> string(String key) {
        Object v = values.get(key);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public String string(String key, String fallback) {
        return string(key).orElse(fallback);
    }

    /** Single-value argument ({@code - quest:accept: <id>}). */
    public Optional<String> value() {
        return string("value");
    }

    public int integer(String key, int fallback) {
        return string(key).flatMap(DialogArgs::parseLong).map(Long::intValue).orElse(fallback);
    }

    public double number(String key, double fallback) {
        return string(key)
                .flatMap(
                        s -> {
                            try {
                                return Optional.of(Double.parseDouble(s.replace("_", "")));
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        })
                .orElse(fallback);
    }

    public boolean bool(String key, boolean fallback) {
        return string(key).map(s -> s.equals("true")).orElse(fallback);
    }

    /** A single string or a list of strings. */
    public List<String> strings(String key) {
        Object v = values.get(key);
        if (v instanceof String s) {
            return List.of(s);
        }
        if (v instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    /** Whether it can be read as an integer (for checks). */
    public boolean isInteger(String key) {
        return string(key).flatMap(DialogArgs::parseLong).isPresent();
    }

    private static Optional<Long> parseLong(String raw) {
        try {
            return Optional.of(Long.parseLong(raw.replace("_", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
