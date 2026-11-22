package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.lang.reflect.Array;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxRegistry;

/**
 * Lambda expression of any return type, optionally changeable (set · add · remove · reset · delete)
 * and optionally a list. Built with {@link #builder} and registered through a {@link Registrar}
 * (counted like the other lambda syntax).
 *
 * <pre>{@code
 * ValueExpr.builder(Number.class, Spec.in("harbor rolled size", QualityRolledEvent.class), getter)
 *         .changer(Number.class, (e, x, mode, value) -> ..., ChangeMode.SET, ChangeMode.ADD)
 *         .register(r, "[the] harbor rolled size");
 * }</pre>
 */
public final class ValueExpr<T> extends SimpleExpression<T> {

    /** Applies a change. value is null for RESET and DELETE. */
    @FunctionalInterface
    public interface Changer {
        void change(Event event, Expression<?>[] exprs, ChangeMode mode, @Nullable Object value);
    }

    private final Builder<T> spec;
    private Expression<?>[] exprs = new Expression<?>[0];

    private ValueExpr(Builder<T> spec) {
        this.spec = spec;
    }

    public static <T> Builder<T> builder(Class<T> type, Spec spec, Spec.Getter<T> getter) {
        return new Builder<>(type, spec, getter);
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        Class<? extends Event> only = spec.spec.onlyIn;
        if (only != null && !getParser().isCurrentEvent(only)) {
            Skript.error("'" + spec.spec.label + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
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
            values = spec.getter.get(event, exprs);
        } catch (RuntimeException e) {
            return (T[]) Array.newInstance(spec.type, 0);
        }
        return values.toArray((T[]) Array.newInstance(spec.type, values.size()));
    }

    @Override
    public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
        Changer changer = spec.changer;
        Class<?> delta = spec.deltaType;
        if (changer == null || delta == null || !spec.modes.contains(mode)) {
            return null;
        }
        return new Class<?>[] {delta};
    }

    @Override
    public void change(Event event, Object @Nullable [] delta, ChangeMode mode) {
        Changer changer = spec.changer;
        if (changer == null) {
            return;
        }
        Object value = delta == null || delta.length == 0 ? null : delta[0];
        if (value == null && mode != ChangeMode.RESET && mode != ChangeMode.DELETE) {
            return;
        }
        try {
            changer.change(event, exprs, mode, value);
        } catch (RuntimeException e) {
            Skript.warning(spec.spec.label + " 변경 오류: " + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public boolean isSingle() {
        if (spec.list) {
            return false;
        }
        if (!spec.spec.perFirstArg || exprs.length == 0) {
            return true;
        }
        Expression<?> first = exprs[0];
        return first == null || first.isSingle();
    }

    @Override
    public Class<? extends T> getReturnType() {
        return spec.type;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return spec.spec.label;
    }

    /** Registration settings (shared by every instance Skript creates). */
    public static final class Builder<T> {

        private final Class<T> type;
        private final Spec spec;
        private final Spec.Getter<T> getter;
        private boolean list;
        private @Nullable Changer changer;
        private @Nullable Class<?> deltaType;
        private final Set<ChangeMode> modes = EnumSet.noneOf(ChangeMode.class);

        private Builder(Class<T> type, Spec spec, Spec.Getter<T> getter) {
            this.type = type;
            this.spec = spec;
            this.getter = getter;
        }

        /** Always a list (any number of values), regardless of the arguments. */
        public Builder<T> list() {
            this.list = true;
            return this;
        }

        /**
         * @param delta type of the value given to set · add · remove (ignored for reset · delete)
         */
        public Builder<T> changer(Class<?> delta, Changer changer, ChangeMode... modes) {
            this.deltaType = delta;
            this.changer = changer;
            this.modes.addAll(List.of(modes));
            return this;
        }

        @SuppressWarnings("unchecked")
        public void register(Registrar r, String... patterns) {
            Class<ValueExpr<T>> cls = (Class<ValueExpr<T>>) (Class<?>) ValueExpr.class;
            r.registry()
                    .register(
                            SyntaxRegistry.EXPRESSION,
                            DefaultSyntaxInfos.Expression.builder(cls, type)
                                    .addPatterns(patterns)
                                    .supplier(() -> new ValueExpr<>(this))
                                    .build());
            r.counted();
        }
    }
}
