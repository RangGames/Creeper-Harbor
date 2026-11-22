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
 * The quest entry being edited (no server API). Holds the YAML as plain Java values (LinkedHashMap,
 * ArrayList, String), so keys the editor does not know are saved unchanged. Objectives are
 * converted to the full form ({key, type, target, count, …}) on load with an explicit key (the same
 * value as the short form's default key or the order number o&lt;n&gt;, so progress carries over).
 * Modified on the main thread only.
 */
public final class QuestDraft {

    /** Types whose value in the short form is an amount (same as Objective). */
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

    /** An entry read from a file. */
    public static QuestDraft of(String file, Map<String, Object> entry) {
        Map<String, Object> data = mutable(entry);
        String id = String.valueOf(data.getOrDefault("id", ""));
        QuestDraft draft = new QuestDraft(id, file, false, data);
        draft.normalizeObjectives();
        return draft;
    }

    /** New quest (template per type). */
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

    /** File to put a new quest in (under quests/). */
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

    /** Short-form or keyless objectives to the full form + explicit key. */
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

    // ------------------------------------------------------------------ Basics

    public String id() {
        return id;
    }

    /** File name under quests/. */
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

    /** The entry to save (a copy). */
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

    // ------------------------------------------------------------------ Description

    public List<String> description() {
        Object v = data.get("description");
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return v == null ? List.of() : List.of(String.valueOf(v));
    }

    /** One line per line (blank lines are dropped). */
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

    // ------------------------------------------------------------------ Objectives

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

    /** New objective. The key is the type + a number that does not collide. */
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

    /**
     * Changes the order (keys stay the same so progress carries over; sequence quests change their
     * progress order).
     */
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

    // ------------------------------------------------------------------ Rewards · requirements

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

    /** Adds to a number (removes the key at 0 or below). */
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

    /** A list inside section (rewards.items, requires.quests etc.). */
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
