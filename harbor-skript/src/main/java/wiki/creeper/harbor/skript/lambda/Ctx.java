package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.util.Timespan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * Call context of an {@link Ext} syntax element: the event, the parsed arguments, the matched
 * pattern index and the parse mark (the {@code (0:a|1:b)} choice). Argument getters return empty
 * when the argument is missing or of another type.
 */
public final class Ctx {

    private final Event event;
    private final Expression<?>[] exprs;
    private final int pattern;
    private final int mark;

    public Ctx(Event event, Expression<?>[] exprs, int pattern, int mark) {
        this.event = event;
        this.exprs = exprs;
        this.pattern = pattern;
        this.mark = mark;
    }

    public Event event() {
        return event;
    }

    /** The raw parsed arguments (for {@link Spec.Getter}-style lambdas). */
    Expression<?>[] exprs() {
        return exprs;
    }

    /** Matched pattern index (for conditions: the positive/negative pair index). */
    public int pattern() {
        return pattern;
    }

    /** Parse mark of the matched pattern. */
    public int mark() {
        return mark;
    }

    /** Whether the (optional) argument was written in the script. */
    public boolean has(int index) {
        return index < exprs.length && exprs[index] != null;
    }

    public @Nullable Object single(int index) {
        return Args.single(event, exprs, index);
    }

    public List<Object> all(int index) {
        List<Object> out = new ArrayList<>();
        if (!has(index)) {
            return out;
        }
        for (Object o : exprs[index].getArray(event)) {
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    public Optional<String> string(int index) {
        return Args.string(event, exprs, index);
    }

    public List<String> strings(int index) {
        return all(index).stream().map(String::valueOf).toList();
    }

    public Optional<Number> number(int index) {
        return Args.number(event, exprs, index);
    }

    public List<Player> players(int index) {
        return Args.players(event, exprs, index);
    }

    public Optional<Player> player(int index) {
        return single(index) instanceof Player p ? Optional.of(p) : Optional.empty();
    }

    /** Offline players (online players included). */
    public List<OfflinePlayer> offline(int index) {
        List<OfflinePlayer> out = new ArrayList<>();
        for (Object o : all(index)) {
            if (o instanceof OfflinePlayer p) {
                out.add(p);
            }
        }
        return out;
    }

    public Optional<OfflinePlayer> offlinePlayer(int index) {
        return single(index) instanceof OfflinePlayer p ? Optional.of(p) : Optional.empty();
    }

    /** A Skript timespan argument as a Duration. */
    public Optional<Duration> duration(int index) {
        return single(index) instanceof Timespan t
                ? Optional.of(Duration.ofMillis(t.getAs(Timespan.TimePeriod.MILLISECOND)))
                : Optional.empty();
    }

    /**
     * Stores values in a (variable) argument, deleting it if values is empty. Ignored if the
     * argument is absent.
     */
    public void set(int index, List<?> values) {
        if (!has(index)) {
            return;
        }
        if (values.isEmpty()) {
            exprs[index].change(event, null, ChangeMode.DELETE);
        } else {
            exprs[index].change(event, values.toArray(), ChangeMode.SET);
        }
    }

    public <T> Optional<T> as(int index, Class<T> type) {
        Object o = single(index);
        return type.isInstance(o) ? Optional.of(type.cast(o)) : Optional.empty();
    }
}
