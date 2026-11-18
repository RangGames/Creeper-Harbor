package wiki.creeper.harbor.npc.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 대화 조건·액션의 인자 (YAML 값 그대로: 문자열, 목록, 맵). 서버 API를 쓰지 않아 harbor-tools도 같은 클래스로 검사한다.
 *
 * <pre>{@code
 * # dialogs/*.yml
 * actions:
 *   - {type: affinity:add, value: 10, once: hansol_intro}
 *   - quest:accept: harbor:quest/main/ch1_dark_lighthouse   # 값 하나면 "value" 인자
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

    /** 문자열 값 (목록·맵이면 빈 값). */
    public Optional<String> string(String key) {
        Object v = values.get(key);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public String string(String key, String fallback) {
        return string(key).orElse(fallback);
    }

    /** 값 하나짜리 인자 ({@code - quest:accept: <id>}). */
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

    /** 문자열 하나 또는 문자열 목록. */
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

    /** 정수로 읽을 수 있는지 (검사용). */
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
