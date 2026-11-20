package wiki.creeper.harbor.quest.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestType;

/**
 * 편집 중인 퀘스트 항목 (서버 API 없음). YAML을 일반 Java 값(LinkedHashMap·ArrayList·String)으로 들고 있어서 편집기가 모르는 키도
 * 그대로 저장된다. 목표는 불러올 때 기본형({key, type, target, count, …})으로 바꾸고 key를 적어 둔다 (축약형 기본 key·순서 번호
 * o&lt;n&gt;와 같은 값이라 진행이 이어진다). 메인 스레드에서만 고친다.
 */
public final class QuestDraft {

    /** 축약형에서 값이 수량인 유형 (Objective와 같다). */
    private static final Set<String> VALUE_IS_COUNT = Set.of("job_level", "job_tier", "reputation");

    private final String id;
    private final String file;
    private final boolean created;
    private final Map<String, Object> data;
    private boolean dirty;

    private QuestDraft(String id, String file, boolean created, Map<String, Object> data) {
        this.id = id;
        this.file = file;
        this.created = created;
        this.data = data;
    }

    /** 파일에서 읽은 항목. */
    public static QuestDraft of(String file, Map<String, Object> entry) {
        Map<String, Object> data = mutable(entry);
        String id = String.valueOf(data.getOrDefault("id", ""));
        QuestDraft draft = new QuestDraft(id, file, false, data);
        draft.normalizeObjectives();
        return draft;
    }

    /** 새 퀘스트 (유형 견본). */
    public static QuestDraft create(String id, String name, QuestType type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("type", type.key());
        if (type == QuestType.MAIN) {
            data.put("chapter", "1");
        }
        data.put("description", new ArrayList<>());
        data.put("objectives", new ArrayList<>());
        data.put("rewards", new LinkedHashMap<>());
        if (type.board()) {
            Map<String, Object> pool = new LinkedHashMap<>();
            pool.put("board", type.key());
            pool.put("weight", "100");
            data.put("pool", pool);
        }
        QuestDraft draft = new QuestDraft(id, fileFor(type), true, data);
        draft.dirty = true;
        return draft;
    }

    /** 새 퀘스트를 넣을 파일 (quests/ 아래). */
    public static String fileFor(QuestType type) {
        return switch (type) {
            case MAIN -> "main.yml";
            case REGION -> "region.yml";
            case JOB -> "job.yml";
            case DAILY, WEEKLY, COLLECT -> "commissions.yml";
            default -> "editor.yml";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
        return out;
    }

    private static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            return mutable(m);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(copy(x)));
            return out;
        }
        return v;
    }

    /** 축약형·key 없는 목표를 기본형 + 명시 key로. */
    private void normalizeObjectives() {
        List<Map<String, Object>> list = objectives();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> o = list.get(i);
            if (o.containsKey("type")) {
                o.putIfAbsent("key", "o" + (i + 1));
                continue;
            }
            String type = null;
            for (String k : o.keySet()) {
                if (Objective.BUILTIN.contains(k)) {
                    type = k;
                    break;
                }
            }
            if (type == null) {
                continue;
            }
            String value = String.valueOf(o.remove(type));
            Map<String, Object> normal = new LinkedHashMap<>();
            if (VALUE_IS_COUNT.contains(type)) {
                normal.put("key", o.getOrDefault("key", Objective.autoKey(type, "")));
                normal.put("type", type);
                normal.put("count", value);
            } else {
                normal.put("key", o.getOrDefault("key", Objective.autoKey(type, value)));
                normal.put("type", type);
                normal.put("target", value.isEmpty() ? "any" : value);
            }
            o.remove("key");
            normal.putAll(o);
            list.set(i, normal);
        }
    }

    // ------------------------------------------------------------------ 기본

    public String id() {
        return id;
    }

    /** quests/ 아래 파일 이름. */
    public String file() {
        return file;
    }

    public boolean created() {
        return created;
    }

    public boolean dirty() {
        return dirty;
    }

    public void saved() {
        dirty = false;
    }

    /** 저장할 항목 (복사본). */
    public Map<String, Object> entry() {
        return mutable(data);
    }

    public String string(String key, String fallback) {
        Object v = data.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public void set(String key, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        dirty = true;
    }

    public QuestType type() {
        String raw = string("type", "region");
        for (QuestType t : QuestType.values()) {
            if (t.key().equals(raw)) {
                return t;
            }
        }
        return QuestType.REGION;
    }

    // ------------------------------------------------------------------ 설명

    public List<String> description() {
        Object v = data.get("description");
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return v == null ? List.of() : List.of(String.valueOf(v));
    }

    /** 줄마다 한 줄 (빈 줄은 뺀다). */
    public void description(String text) {
        List<Object> lines = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        data.put("description", lines);
        dirty = true;
    }

    // ------------------------------------------------------------------ 목표

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> objectives() {
        Object v = data.get("objectives");
        if (!(v instanceof List<?>)) {
            v = new ArrayList<>();
            data.put("objectives", v);
        }
        List<Object> raw = (List<Object>) v;
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?>)) {
                raw.set(i, new LinkedHashMap<String, Object>());
            }
        }
        return (List<Map<String, Object>>) (List<?>) raw;
    }

    /** 새 목표. key는 유형 + 겹치지 않는 번호. */
    public Map<String, Object> addObjective(String type) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("key", freeKey(type));
        o.put("type", type);
        if (VALUE_IS_COUNT.contains(type)) {
            o.put("count", "1");
        } else {
            o.put("target", "any");
            o.put("count", "1");
        }
        objectives().add(o);
        dirty = true;
        return o;
    }

    public String freeKey(String base) {
        Set<String> used = new java.util.HashSet<>();
        objectives().forEach(o -> used.add(String.valueOf(o.getOrDefault("key", ""))));
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String k = base + "_" + i;
            if (!used.contains(k)) {
                return k;
            }
        }
    }

    public void removeObjective(int index) {
        objectives().remove(index);
        dirty = true;
    }

    /** 순서를 바꾼다 (key는 그대로라 진행은 이어진다. sequence 퀘스트는 진행 순서가 바뀐다). */
    public void moveObjective(int index, int delta) {
        List<Map<String, Object>> list = objectives();
        int to = index + delta;
        if (index < 0 || index >= list.size() || to < 0 || to >= list.size()) {
            return;
        }
        Map<String, Object> o = list.remove(index);
        list.add(to, o);
        dirty = true;
    }

    public void objectiveSet(Map<String, Object> o, String key, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            o.remove(key);
        } else {
            o.put(key, value);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ 보상 · 요구

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String key) {
        Object v = data.get(key);
        if (!(v instanceof Map<?, ?>)) {
            v = new LinkedHashMap<String, Object>();
            data.put(key, v);
        }
        return (Map<String, Object>) v;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 수치를 더한다 (0 이하면 키를 지운다). */
    public int add(String section, String key, int delta) {
        Map<String, Object> s = section(section);
        int next = Math.max(0, integer(s, key, 0) + delta);
        if (next == 0) {
            s.remove(key);
        } else {
            s.put(key, String.valueOf(next));
        }
        dirty = true;
        return next;
    }

    /** section 안의 목록 (rewards.items, requires.quests 등). */
    @SuppressWarnings("unchecked")
    public List<Object> list(String section, String key) {
        Map<String, Object> s = section(section);
        Object v = s.get(key);
        if (!(v instanceof List<?>)) {
            v = new ArrayList<>(v == null ? List.of() : List.of(v));
            s.put(key, v);
        }
        return (List<Object>) v;
    }

    public void touch() {
        dirty = true;
    }
}
