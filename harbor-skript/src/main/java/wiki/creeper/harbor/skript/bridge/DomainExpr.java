package wiki.creeper.harbor.skript.bridge;

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
import java.util.function.Function;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxRegistry;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * Lambda expression of any return type (Location, ItemStack, Number …) with an optional changer,
 * for the NPC · Economy · World · Boss · Shop bridges. {@link Registrar} only offers number, text
 * and player expressions without changers, so this builds on its registry instead.
 *
 * <pre>{@code
 * DomainExpr.of(Location.class, "harbor npc location")
 *         .get((e, x) -> ...)
 *         .register(r, "[the] harbor npc location of %string%");
 * }</pre>
 */
final class DomainExpr<T> extends SimpleExpression<T> {

    /** Changer: value is the first delta value (null for DELETE · RESET). */
    @FunctionalInterface
    interface Changer {
        void change(Event event, Expression<?>[] exprs, ChangeMode mode, @Nullable Object value);
    }

    private final Def<T> def;
    private Expression<?>[] exprs = new Expression<?>[0];

    private DomainExpr(Def<T> def) {
        this.def = def;
    }

    private record Def<T>(
            Class<T> type,
            String label,
            boolean perFirstArg,
            @Nullable Class<? extends Event> onlyIn,
            Spec.Getter<T> getter,
            @Nullable Changer changer,
            Set<ChangeMode> modes,
            Class<?> changeType) {}

    static <T> Builder<T> of(Class<T> type, String label) {
        return new Builder<>(type, label);
    }

    /** A value from the current event if it is of that type (empty otherwise). */
    static <E extends Event, V> Spec.Getter<V> from(Class<E> type, Function<E, @Nullable V> f) {
        return (e, x) -> {
            if (!type.isInstance(e)) {
                return List.of();
            }
            V v = f.apply(type.cast(e));
            return v == null ? List.of() : List.of(v);
        };
    }

    static final class Builder<T> {
        private final Class<T> type;
        private final String label;
        private boolean perFirstArg = true;

        private @Nullable Class<? extends Event> onlyIn;

        private Spec.Getter<T> getter = (e, x) -> List.of();
        private @Nullable Changer changer;
        private Set<ChangeMode> modes = EnumSet.noneOf(ChangeMode.class);
        private Class<?> changeType = Object.class;

        private Builder(Class<T> type, String label) {
            this.type = type;
            this.label = label;
        }

        /** Always a single value (not one value per first argument). */
        Builder<T> single() {
            perFirstArg = false;
            return this;
        }

        /** Usable only inside this event (also single). */
        Builder<T> in(Class<? extends Event> event) {
            onlyIn = event;
            perFirstArg = false;
            return this;
        }

        Builder<T> get(Spec.Getter<T> getter) {
            this.getter = getter;
            return this;
        }

        Builder<T> change(Class<?> changeType, Set<ChangeMode> modes, Changer changer) {
            this.changeType = changeType;
            this.modes = EnumSet.copyOf(modes);
            this.changer = changer;
            return this;
        }

        void register(Registrar r, String... patterns) {
            Def<T> def =
                    new Def<>(type, label, perFirstArg, onlyIn, getter, changer, modes, changeType);
            @SuppressWarnings("unchecked")
            Class<DomainExpr<T>> cls = (Class<DomainExpr<T>>) (Class<?>) DomainExpr.class;
            r.registry()
                    .register(
                            SyntaxRegistry.EXPRESSION,
                            DefaultSyntaxInfos.Expression.builder(cls, type)
                                    .addPatterns(patterns)
                                    .supplier(() -> new DomainExpr<>(def))
                                    .build());
            r.counted();
        }
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        Class<? extends Event> only = def.onlyIn();
        if (only != null && !getParser().isCurrentEvent(only)) {
            Skript.error("'" + def.label() + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
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
            values = def.getter().get(event, exprs);
        } catch (RuntimeException e) {
            values = List.of();
        }
        return values.toArray((T[]) Array.newInstance(def.type(), values.size()));
    }

    @Override
    public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
        return def.changer() != null && def.modes().contains(mode)
                ? new Class<?>[] {def.changeType()}
                : null;
    }

    @Override
    public void change(Event event, Object @Nullable [] delta, ChangeMode mode) {
        Changer c = def.changer();
        if (c == null) {
            return;
        }
        Object value = delta == null || delta.length == 0 ? null : delta[0];
        try {
            c.change(event, exprs, mode, value);
        } catch (RuntimeException e) {
            Skript.warning(def.label() + " 변경 오류: " + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public boolean isSingle() {
        if (!def.perFirstArg() || exprs.length == 0) {
            return true;
        }
        Expression<?> first = exprs[0];
        return first == null || first.isSingle();
    }

    @Override
    public Class<? extends T> getReturnType() {
        return def.type();
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return def.label();
    }
}
