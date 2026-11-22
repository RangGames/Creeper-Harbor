package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Set;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * Lambda expression of any return type (Location, ItemStack, Number …), optionally a list, with an
 * optional changer (set · add · remove · reset · delete) and access to the parse mark. Registered
 * through {@link Ext#expr}. The getter and changer take either a {@link Ctx} or the raw {@code
 * (event, exprs)} pair ({@link Spec.Getter}, {@link ValueChanger}).
 */
public final class ExtExpr<T> extends SimpleExpression<T> {

    /** How many values the expression returns. */
    public enum Card {
        /** Always one value. */
        SINGLE,
        /** One value per value of one argument (by default the first, usually %players%). */
        PER_FIRST,
        /** Any number of values. */
        LIST
    }

    /** Value list (empty if none). */
    @FunctionalInterface
    public interface Getter<T> {
        List<? extends T> get(Ctx ctx);
    }

    /** Applies a change. delta is null for reset and delete. */
    @FunctionalInterface
    public interface Changer {
        void change(Ctx ctx, Object @Nullable [] delta, ChangeMode mode);
    }

    /**
     * Applies a change given the first delta value (null for reset and delete). See {@link
     * Ext.ExprBuilder#valueChanger} and {@link Ext.ExprBuilder#valueChangerNullable} for when it is
     * called without a value.
     */
    @FunctionalInterface
    public interface ValueChanger {
        void change(Event event, Expression<?>[] exprs, ChangeMode mode, @Nullable Object value);
    }

    /** Settings shared by every instance Skript creates for one registration. */
    record Info<T>(
            String label,
            Class<T> type,
            Card card,
            int perArg,
            @Nullable Class<? extends Event> onlyIn,
            Getter<T> getter,
            @Nullable Changer changer,
            Class<?> deltaType,
            Set<ChangeMode> modes) {}

    private final Info<T> info;
    private Expression<?>[] exprs = new Expression<?>[0];
    private int pattern;
    private int mark;

    ExtExpr(Info<T> info) {
        this.info = info;
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        Class<? extends Event> only = info.onlyIn();
        if (only != null && !getParser().isCurrentEvent(only)) {
            Skript.error("'" + info.label() + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
            return false;
        }
        this.exprs = exprs;
        this.pattern = pattern;
        this.mark = parseResult.mark;
        return true;
    }

    private Ctx ctx(Event event) {
        return new Ctx(event, exprs, pattern, mark);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T @Nullable [] get(Event event) {
        List<? extends T> values;
        try {
            values = info.getter().get(ctx(event));
        } catch (RuntimeException e) {
            return (T[]) Array.newInstance(info.type(), 0);
        }
        T[] out = (T[]) Array.newInstance(info.type(), values.size());
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    @Override
    public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
        if (info.changer() == null || !info.modes().contains(mode)) {
            return null;
        }
        return new Class<?>[] {info.deltaType()};
    }

    @Override
    public void change(Event event, Object @Nullable [] delta, ChangeMode mode) {
        Changer changer = info.changer();
        if (changer == null) {
            return;
        }
        try {
            changer.change(ctx(event), delta, mode);
        } catch (RuntimeException e) {
            Skript.warning(info.label() + " 변경 오류: " + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public boolean isSingle() {
        return switch (info.card()) {
            case SINGLE -> true;
            case LIST -> false;
            case PER_FIRST -> {
                int i = info.perArg();
                yield i >= exprs.length || exprs[i] == null || exprs[i].isSingle();
            }
        };
    }

    @Override
    public Class<? extends T> getReturnType() {
        return info.type();
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return info.label();
    }
}
