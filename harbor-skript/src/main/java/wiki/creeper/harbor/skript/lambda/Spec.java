package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.lang.Expression;
import java.util.List;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * Information about one lambda syntax element (set at registration and shared by every instance
 * Skript creates). Lambdas that use module classes are created only inside that module's bridge
 * class (that class is not loaded when the module is disabled).
 */
public final class Spec {

    /** Expression: list of values. Empty list if there is no value. */
    @FunctionalInterface
    public interface Getter<T> {
        List<T> get(Event event, Expression<?>[] exprs);
    }

    /** Condition. */
    @FunctionalInterface
    public interface Check {
        boolean test(Event event, Expression<?>[] exprs);
    }

    /** Effect. */
    @FunctionalInterface
    public interface Action {
        void run(Event event, Expression<?>[] exprs);
    }

    /** The event class if this expression is usable only inside that event (null otherwise). */
    final @Nullable Class<? extends Event> onlyIn;

    final String label;

    /**
     * If true, single-valued only when the first argument (player list etc.) is a single value. If
     * false, always single.
     */
    final boolean perFirstArg;

    private Spec(String label, boolean perFirstArg, @Nullable Class<? extends Event> onlyIn) {
        this.label = label;
        this.perFirstArg = perFirstArg;
        this.onlyIn = onlyIn;
    }

    /** One value per first argument (usually %players%). */
    public static Spec of(String label) {
        return new Spec(label, true, null);
    }

    /** Always one value (a server-wide value, or a single argument). */
    public static Spec single(String label) {
        return new Spec(label, false, null);
    }

    /** Only inside that event (a named expression instead of an event-value). */
    public static Spec in(String label, Class<? extends Event> event) {
        return new Spec(label, false, event);
    }
}
