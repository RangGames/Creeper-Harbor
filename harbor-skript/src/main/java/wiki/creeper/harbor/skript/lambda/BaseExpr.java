package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.lang.reflect.Array;
import java.util.List;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * 람다 식의 공통 부분. 반환 형식마다 하위 클래스가 하나씩 있다 ({@link NumberExpr} · {@link TextExpr} · {@link
 * OfflinePlayerExpr}).
 */
abstract class BaseExpr<T> extends SimpleExpression<T> {

    private final Spec spec;
    private final Spec.Getter<T> getter;
    private final Class<T> type;
    private Expression<?>[] exprs = new Expression<?>[0];

    BaseExpr(Spec spec, Class<T> type, Spec.Getter<T> getter) {
        this.spec = spec;
        this.type = type;
        this.getter = getter;
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        Class<? extends Event> only = spec.onlyIn;
        if (only != null && !getParser().isCurrentEvent(only)) {
            Skript.error("'" + spec.label + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
            return false;
        }
        this.exprs = exprs;
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T @Nullable [] get(Event event) {
        List<T> values;
        try {
            values = getter.get(event, exprs);
        } catch (RuntimeException e) {
            return (T[]) Array.newInstance(type, 0);
        }
        return values.toArray((T[]) Array.newInstance(type, values.size()));
    }

    @Override
    public boolean isSingle() {
        if (!spec.perFirstArg || exprs.length == 0) {
            return true;
        }
        Expression<?> first = exprs[0];
        return first == null || first.isSingle();
    }

    @Override
    public Class<? extends T> getReturnType() {
        return type;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return spec.label;
    }
}
