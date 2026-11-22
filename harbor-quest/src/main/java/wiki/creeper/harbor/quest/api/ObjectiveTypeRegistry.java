package wiki.creeper.harbor.quest.api;

import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/**
 * Objective type registration (docs 20 6.2). Register in the module's enable (before content loads)
 * so the content check knows the type. Registered types are advanced through {@link
 * ObjectiveSignal}.
 */
public interface ObjectiveTypeRegistry {

    /** Checks one objective during the content check. Reports problems through error. */
    @FunctionalInterface
    interface Validator {
        void validate(ObjectiveSpec objective, Consumer<String> error);
    }

    /** Whether a signal target matches the objective target. */
    @FunctionalInterface
    interface Matcher {
        boolean matches(ObjectiveSpec objective, String signalTarget);
    }

    /**
     * Summary of one objective (the value passed to other modules).
     *
     * @param target the target notation as written
     */
    record ObjectiveSpec(String quest, String key, String type, String target, int count) {}

    /**
     * IllegalStateException if the key already exists. Removed automatically when owner is
     * disabled.
     */
    void register(Plugin owner, String key, Validator validator, Matcher matcher);

    /** Registered types (including the built-in types). */
    Set<String> types();
}
