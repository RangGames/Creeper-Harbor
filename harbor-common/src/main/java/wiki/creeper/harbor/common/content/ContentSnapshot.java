package wiki.creeper.harbor.common.content;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Immutable snapshot of all content. A reload builds a new snapshot and swaps a single reference,
 * so readers can use it without locking.
 */
public final class ContentSnapshot {

    private static final ContentSnapshot EMPTY = new ContentSnapshot(Map.of(), Map.of());

    private final Map<String, Map<HarborId, Object>> entries;
    private final Map<String, Object> singles;

    /**
     * References collected per type during parsing. Kept so that a partial reload can recheck
     * references of types that were not re-read.
     */
    private final Map<String, List<ParseContext.Reference>> references;

    ContentSnapshot(Map<String, Map<HarborId, Object>> entries, Map<String, Object> singles) {
        this(entries, singles, Map.of());
    }

    ContentSnapshot(
            Map<String, Map<HarborId, Object>> entries,
            Map<String, Object> singles,
            Map<String, List<ParseContext.Reference>> references) {
        Map<String, Map<HarborId, Object>> copy = new HashMap<>();
        entries.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(v)));
        this.entries = Collections.unmodifiableMap(copy);
        this.singles = Map.copyOf(singles);
        Map<String, List<ParseContext.Reference>> refs = new HashMap<>();
        references.forEach((k, v) -> refs.put(k, List.copyOf(v)));
        this.references = Collections.unmodifiableMap(refs);
    }

    public static ContentSnapshot empty() {
        return EMPTY;
    }

    /** All entries of a type (by ID). */
    @SuppressWarnings("unchecked")
    public <T> Map<HarborId, T> all(ContentType.Entries<T> type) {
        return (Map<HarborId, T>) (Map<HarborId, ?>) entries.getOrDefault(type.name(), Map.of());
    }

    public <T> Optional<T> get(ContentType.Entries<T> type, HarborId id) {
        return Optional.ofNullable(all(type).get(id));
    }

    /** Value of a single-file type. IllegalStateException if the type is not registered. */
    @SuppressWarnings("unchecked")
    public <T> T single(ContentType.Single<T> type) {
        @Nullable Object value = singles.get(type.name());
        if (value == null) {
            throw new IllegalStateException("콘텐츠 형식 '" + type.name() + "'가 로드되지 않았습니다");
        }
        return (T) value;
    }

    public boolean has(String typeName, HarborId id) {
        Map<HarborId, Object> map = entries.get(typeName);
        return map != null && map.containsKey(id);
    }

    /**
     * One entry by type name (for callers that do not know the type class: resolving names from
     * external event IDs, etc.).
     */
    public Optional<Object> entry(String typeName, HarborId id) {
        Map<HarborId, Object> map = entries.get(typeName);
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(id));
    }

    public Set<HarborId> ids(String typeName) {
        Map<HarborId, Object> map = entries.get(typeName);
        return map == null ? Set.of() : map.keySet();
    }

    /** Type names contained in this snapshot. */
    public Set<String> typeNames() {
        Set<String> names = new TreeSet<>(entries.keySet());
        names.addAll(singles.keySet());
        return names;
    }

    Map<String, Map<HarborId, Object>> rawEntries() {
        return entries;
    }

    Map<String, Object> rawSingles() {
        return singles;
    }

    Map<String, List<ParseContext.Reference>> rawReferences() {
        return references;
    }

    /** Entry count per type (for status display). */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new TreeMap<>();
        entries.forEach((k, v) -> counts.put(k, v.size()));
        singles.keySet().forEach(k -> counts.put(k, 1));
        return counts;
    }
}
