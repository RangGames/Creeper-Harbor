package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.classes.Changer.ChangeMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

/**
 * Extended lambda registration on top of {@link Registrar}: expressions of any type with changers,
 * effects and conditions that see the parse mark, and {@link AwaitEffect awaiting effects} for
 * asynchronous results. Every registration is counted on the Registrar.
 */
public final class Ext {

    private Ext() {}

    /** Starts an expression registration. */
    public static <T> ExprBuilder<T> expr(Registrar r, Class<T> type, String label) {
        return new ExprBuilder<>(r, type, label);
    }

    /**
     * Starts an expression registration from a {@link Spec} (label, cardinality, event) and a
     * {@code (event, exprs)} getter.
     *
     * <pre>{@code
     * Ext.expr(r, Number.class, Spec.in("harbor rolled size", QualityRolledEvent.class), getter)
     *         .valueChanger(Number.class, (e, x, mode, value) -> ..., ChangeMode.SET)
     *         .register("[the] harbor rolled size");
     * }</pre>
     */
    public static <T> ExprBuilder<T> expr(
            Registrar r, Class<T> type, Spec spec, Spec.Getter<T> getter) {
        ExprBuilder<T> b = new ExprBuilder<>(r, type, spec.label);
        b.card = spec.perFirstArg ? ExtExpr.Card.PER_FIRST : ExtExpr.Card.SINGLE;
        b.onlyIn = spec.onlyIn;
        return b.get(getter);
    }

    public static void effect(Registrar r, String label, ExtStatement.Action action, String... p) {
        effectIn(r, label, null, action, p);
    }

    /** Effect usable only inside that event. */
    public static void effectIn(
            Registrar r,
            String label,
            @Nullable Class<? extends Event> onlyIn,
            ExtStatement.Action action,
            String... patterns) {
        r.registry()
                .register(
                        SyntaxRegistry.EFFECT,
                        SyntaxInfo.builder(ExtStatement.Eff.class)
                                .addPatterns(patterns)
                                .supplier(() -> new ExtStatement.Eff(label, onlyIn, action))
                                .build());
        r.counted();
    }

    /**
     * @param patterns positive and negative patterns alternating (even index = positive)
     */
    public static void condition(
            Registrar r, String label, ExtStatement.Check check, String... patterns) {
        conditionIn(r, label, null, check, patterns);
    }

    public static void conditionIn(
            Registrar r,
            String label,
            @Nullable Class<? extends Event> onlyIn,
            ExtStatement.Check check,
            String... patterns) {
        r.registry()
                .register(
                        SyntaxRegistry.CONDITION,
                        SyntaxInfo.builder(ExtStatement.Cond.class)
                                .addPatterns(patterns)
                                .supplier(() -> new ExtStatement.Cond(label, onlyIn, check))
                                .build());
        r.counted();
    }

    /**
     * Awaiting effect.
     *
     * @param targets indices of the variable arguments the results are stored in
     */
    public static void await(
            Registrar r, String label, int[] targets, AwaitEffect.Start start, String... patterns) {
        r.registry()
                .register(
                        SyntaxRegistry.EFFECT,
                        SyntaxInfo.builder(AwaitEffect.class)
                                .addPatterns(patterns)
                                .supplier(() -> new AwaitEffect(label, targets, start))
                                .build());
        r.counted();
    }

    /** One result list for a single target. */
    public static CompletableFuture<List<?>[]> one(CompletableFuture<? extends List<?>> future) {
        return future.thenApply(list -> new List<?>[] {list});
    }

    /** A single value (empty if null) as a one-target result. */
    public static CompletableFuture<List<?>[]> value(CompletableFuture<?> future) {
        return future.thenApply(v -> new List<?>[] {Args.one(v)});
    }

    /** Event-value registrations for a UUID player: offline player always, player if online. */
    public static <E extends Event> void playerValues(
            Registrar r, Class<E> event, java.util.function.Function<E, java.util.UUID> uuid) {
        r.value(event, OfflinePlayer.class, e -> Bukkit.getOfflinePlayer(uuid.apply(e)));
        r.value(event, Player.class, e -> Bukkit.getPlayer(uuid.apply(e)));
    }

    /** Expression registration builder. */
    public static final class ExprBuilder<T> {

        private final Registrar r;
        private final Class<T> type;
        private final String label;
        private ExtExpr.Card card = ExtExpr.Card.PER_FIRST;
        private int perArg;
        private @Nullable Class<? extends Event> onlyIn;
        private ExtExpr.@Nullable Changer changer;
        private Class<?> deltaType = Object.class;
        private Set<ChangeMode> modes = EnumSet.noneOf(ChangeMode.class);
        private ExtExpr.Getter<T> getter = ctx -> List.of();

        private ExprBuilder(Registrar r, Class<T> type, String label) {
            this.r = r;
            this.type = type;
            this.label = label;
        }

        /** Always one value. */
        public ExprBuilder<T> single() {
            card = ExtExpr.Card.SINGLE;
            return this;
        }

        /** One value per value of the argument at that index. */
        public ExprBuilder<T> perArg(int index) {
            card = ExtExpr.Card.PER_FIRST;
            perArg = index;
            return this;
        }

        /** Any number of values. */
        public ExprBuilder<T> list() {
            card = ExtExpr.Card.LIST;
            return this;
        }

        /** Only inside that event (a single value). */
        public ExprBuilder<T> in(Class<? extends Event> event) {
            onlyIn = event;
            card = ExtExpr.Card.SINGLE;
            return this;
        }

        public ExprBuilder<T> changer(
                Class<?> deltaType, ExtExpr.Changer changer, ChangeMode... modes) {
            this.deltaType = deltaType;
            this.changer = changer;
            this.modes = EnumSet.noneOf(ChangeMode.class);
            this.modes.addAll(List.of(modes));
            return this;
        }

        /**
         * Changer given the first delta value. Not called when set · add · remove come without a
         * value; called with null for reset · delete.
         */
        public ExprBuilder<T> valueChanger(
                Class<?> deltaType, ExtExpr.ValueChanger changer, ChangeMode... modes) {
            return changer(deltaType, firstValue(changer, true), modes);
        }

        /** Changer given the first delta value, or null whenever there is none (any mode). */
        public ExprBuilder<T> valueChangerNullable(
                Class<?> deltaType, Set<ChangeMode> modes, ExtExpr.ValueChanger changer) {
            return changer(deltaType, firstValue(changer, false), modes.toArray(new ChangeMode[0]));
        }

        private static ExtExpr.Changer firstValue(ExtExpr.ValueChanger changer, boolean skipEmpty) {
            return (ctx, delta, mode) -> {
                Object value = delta == null || delta.length == 0 ? null : delta[0];
                if (skipEmpty
                        && value == null
                        && mode != ChangeMode.RESET
                        && mode != ChangeMode.DELETE) {
                    return;
                }
                changer.change(ctx.event(), ctx.exprs(), mode, value);
            };
        }

        /** Getter on the raw {@code (event, exprs)} pair (used by {@link #register(String...)}). */
        public ExprBuilder<T> get(Spec.Getter<T> getter) {
            this.getter = ctx -> getter.get(ctx.event(), ctx.exprs());
            return this;
        }

        /** Registers with the getter set by {@link #get} (none: always empty). */
        public void register(String... patterns) {
            register(getter, patterns);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public void register(ExtExpr.Getter<T> getter, String... patterns) {
            ExtExpr.Info<T> info =
                    new ExtExpr.Info<>(
                            label, type, card, perArg, onlyIn, getter, changer, deltaType, modes);
            Class<ExtExpr<T>> cls = (Class<ExtExpr<T>>) (Class) ExtExpr.class;
            r.registry()
                    .register(
                            SyntaxRegistry.EXPRESSION,
                            DefaultSyntaxInfos.Expression.builder(cls, type)
                                    .addPatterns(patterns)
                                    .supplier(() -> new ExtExpr<>(info))
                                    .build());
            r.counted();
        }
    }
}
