package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.lang.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/** 람다 문법의 인자 꺼내기. 인자가 없거나 형식이 다르면 빈 값. */
public final class Args {

    private Args() {}

    public static List<Player> players(Event event, Expression<?>[] exprs, int index) {
        List<Player> out = new ArrayList<>();
        if (index >= exprs.length || exprs[index] == null) {
            return out;
        }
        for (Object o : exprs[index].getArray(event)) {
            if (o instanceof Player p) {
                out.add(p);
            }
        }
        return out;
    }

    public static Optional<String> string(Event event, Expression<?>[] exprs, int index) {
        Object o = single(event, exprs, index);
        return o == null ? Optional.empty() : Optional.of(String.valueOf(o));
    }

    public static Optional<Number> number(Event event, Expression<?>[] exprs, int index) {
        return single(event, exprs, index) instanceof Number n ? Optional.of(n) : Optional.empty();
    }

    public static @Nullable Object single(Event event, Expression<?>[] exprs, int index) {
        if (index >= exprs.length || exprs[index] == null) {
            return null;
        }
        return exprs[index].getSingle(event);
    }

    /** 플레이어마다 값 하나 (값이 없는 플레이어는 뺀다). */
    public static <T> List<T> perPlayer(
            Event event, Expression<?>[] exprs, Function<Player, @Nullable T> value) {
        List<T> out = new ArrayList<>();
        for (Player p : players(event, exprs, 0)) {
            T v = value.apply(p);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** 모든 플레이어가 조건을 만족하는지 (플레이어가 없으면 false). */
    public static boolean all(
            Event event, Expression<?>[] exprs, java.util.function.Predicate<Player> test) {
        List<Player> players = players(event, exprs, 0);
        return !players.isEmpty() && players.stream().allMatch(test);
    }

    /** 값 하나짜리 목록 (null이면 빈 목록). */
    public static <T> List<T> one(@Nullable T value) {
        return value == null ? List.of() : List.of(value);
    }
}
