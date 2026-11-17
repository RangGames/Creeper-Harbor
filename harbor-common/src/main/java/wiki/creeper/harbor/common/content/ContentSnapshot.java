package wiki.creeper.harbor.common.content;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 콘텐츠 전체의 변경 불가 스냅샷. 리로드는 새 스냅샷을 만들어 참조 하나를 바꾸는 방식이라, 읽는 쪽은 잠금 없이 써도 된다.
 */
public final class ContentSnapshot {

    private static final ContentSnapshot EMPTY = new ContentSnapshot(Map.of(), Map.of());

    private final Map<String, Map<HarborId, Object>> entries;
    private final Map<String, Object> singles;

    ContentSnapshot(Map<String, Map<HarborId, Object>> entries, Map<String, Object> singles) {
        Map<String, Map<HarborId, Object>> copy = new HashMap<>();
        entries.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(v)));
        this.entries = Collections.unmodifiableMap(copy);
        this.singles = Map.copyOf(singles);
    }

    public static ContentSnapshot empty() {
        return EMPTY;
    }

    /** 형식의 모든 항목 (ID 순). */
    @SuppressWarnings("unchecked")
    public <T> Map<HarborId, T> all(ContentType.Entries<T> type) {
        return (Map<HarborId, T>) (Map<HarborId, ?>) entries.getOrDefault(type.name(), Map.of());
    }

    public <T> Optional<T> get(ContentType.Entries<T> type, HarborId id) {
        return Optional.ofNullable(all(type).get(id));
    }

    /** 파일 하나짜리 형식의 값. 등록되지 않은 형식이면 IllegalStateException. */
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

    public Set<HarborId> ids(String typeName) {
        Map<HarborId, Object> map = entries.get(typeName);
        return map == null ? Set.of() : map.keySet();
    }

    /** 이 스냅샷에 들어 있는 형식 이름들. */
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

    /** 형식별 항목 수 (상태 표시용). */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new TreeMap<>();
        entries.forEach((k, v) -> counts.put(k, v.size()));
        singles.keySet().forEach(k -> counts.put(k, 1));
        return counts;
    }
}
