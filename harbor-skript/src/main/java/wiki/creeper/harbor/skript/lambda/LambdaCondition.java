package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * Lambda condition. An odd pattern index is the negated form (positive and negative patterns
 * alternate at registration).
 */
public final class LambdaCondition extends Condition {

    private final Spec spec;
    private final Spec.Check check;
    private Expression<?>[] exprs = new Expression<?>[0];

    public LambdaCondition(Spec spec, Spec.Check check) {
        this.spec = spec;
        this.check = check;
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        this.exprs = exprs;
        setNegated(pattern % 2 == 1);
        return true;
    }

    @Override
    public boolean check(Event event) {
        boolean result;
        try {
            result = check.test(event, exprs);
        } catch (RuntimeException e) {
            result = false;
        }
        return result != isNegated();
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return (isNegated() ? "not " : "") + spec.label;
    }
}
