package wiki.creeper.harbor.common.model;

import java.util.Locale;
import java.util.Optional;

/** Enum stored in YAML/DB as a lowercase key. */
public interface Keyed {

    String key();

    static String keyOf(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E> & Keyed> Optional<E> fromKey(Class<E> type, String key) {
        for (E value : type.getEnumConstants()) {
            if (value.key().equals(key)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
