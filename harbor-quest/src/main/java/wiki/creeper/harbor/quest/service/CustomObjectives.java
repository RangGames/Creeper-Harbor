package wiki.creeper.harbor.quest.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry.Matcher;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry.ObjectiveSpec;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry.Validator;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.logic.Matchers;

/**
 * Objective types registered by other modules (ObjectiveTypeRegistry). Removed when the registering
 * plugin is disabled. Safe to read from any thread.
 */
final class CustomObjectives {

    private record CustomType(Plugin owner, Validator validator, Matcher matcher) {}

    private final Logger log;
    private final Map<String, CustomType> types = new ConcurrentHashMap<>();

    CustomObjectives(Logger log) {
        this.log = log;
    }

    void register(Plugin owner, String key, Validator validator, Matcher matcher) {
        if (Objective.BUILTIN.contains(key)
                || types.putIfAbsent(key, new CustomType(owner, validator, matcher)) != null) {
            throw new IllegalStateException("이미 등록된 목표 유형입니다: " + key);
        }
    }

    Set<String> types() {
        Set<String> all = new HashSet<>(Objective.BUILTIN);
        all.addAll(types.keySet());
        return Set.copyOf(all);
    }

    void unregisterOwner(Plugin owner) {
        types.values().removeIf(t -> t.owner().equals(owner));
    }

    /** Content check: validates arguments of other modules' types. */
    void validate(QuestDef quest, Consumer<String> error) {
        for (Objective o : quest.objectives()) {
            CustomType custom = types.get(o.type());
            if (custom != null) {
                custom.validator().validate(spec(quest.id().toString(), o), error);
            }
        }
    }

    boolean matches(Objective o, String type, String target) {
        CustomType custom = types.get(type);
        if (custom != null) {
            try {
                return custom.matcher().matches(spec("", o), target);
            } catch (RuntimeException e) {
                log.warn("목표 유형 {} 판정 오류: {}", type, String.valueOf(e));
                return false;
            }
        }
        return Matchers.matchesRaw(o, target);
    }

    static ObjectiveSpec spec(String quest, Objective o) {
        return new ObjectiveSpec(quest, o.key(), o.type(), o.target().raw(), o.count());
    }
}
