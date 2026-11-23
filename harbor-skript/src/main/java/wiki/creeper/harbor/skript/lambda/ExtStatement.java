package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/** Effect and condition variants of {@link Ext} that see the pattern index and parse mark. */
public final class ExtStatement {

    private ExtStatement() {}

    /** Effect body. */
    @FunctionalInterface
    public interface Action {
        void run(Ctx ctx);
    }

    /** Condition body (before negation). */
    @FunctionalInterface
    public interface Check {
        boolean test(Ctx ctx);
    }

    /** Effect. Optionally only valid inside one event. */
    public static final class Eff extends Effect {

        private final String label;
        private final @Nullable Class<? extends Event> onlyIn;
        private final Action action;
        private final boolean audited;
        private Expression<?>[] exprs = new Expression<?>[0];
        private int pattern;
        private int mark;

        Eff(String label, @Nullable Class<? extends Event> onlyIn, Action action, boolean audited) {
            this.label = label;
            this.onlyIn = onlyIn;
            this.action = action;
            this.audited = audited;
        }

        @Override
        public boolean init(
                Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
            Class<? extends Event> only = onlyIn;
            if (only != null && !getParser().isCurrentEvent(only)) {
                Skript.error("'" + label + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
                return false;
            }
            this.exprs = exprs;
            this.pattern = pattern;
            this.mark = parseResult.mark;
            return true;
        }

        @Override
        protected void execute(Event event) {
            try {
                action.run(new Ctx(event, exprs, pattern, mark));
            } catch (RuntimeException e) {
                Skript.warning(label + " 실행 오류: " + String.valueOf(e.getMessage()));
            }
            if (audited) {
                AdminAudit.record(this, event, label, exprs);
            }
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return label;
        }
    }

    /**
     * Condition. Patterns are registered in positive/negative pairs, so an odd pattern index is the
     * negated form; the ctx pattern index is halved (the pair index).
     */
    public static final class Cond extends Condition {

        private final String label;
        private final @Nullable Class<? extends Event> onlyIn;
        private final Check check;
        private Expression<?>[] exprs = new Expression<?>[0];
        private int pattern;
        private int mark;

        Cond(String label, @Nullable Class<? extends Event> onlyIn, Check check) {
            this.label = label;
            this.onlyIn = onlyIn;
            this.check = check;
        }

        @Override
        public boolean init(
                Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
            Class<? extends Event> only = onlyIn;
            if (only != null && !getParser().isCurrentEvent(only)) {
                Skript.error("'" + label + "'은(는) 그 이벤트 안에서만 쓸 수 있습니다");
                return false;
            }
            this.exprs = exprs;
            this.pattern = pattern / 2;
            this.mark = parseResult.mark;
            setNegated(pattern % 2 == 1);
            return true;
        }

        @Override
        public boolean check(Event event) {
            boolean result;
            try {
                result = check.test(new Ctx(event, exprs, pattern, mark));
            } catch (RuntimeException e) {
                result = false;
            }
            return result != isNegated();
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return (isNegated() ? "not " : "") + label;
        }
    }
}
