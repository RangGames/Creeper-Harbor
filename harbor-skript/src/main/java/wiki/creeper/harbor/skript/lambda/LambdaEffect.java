package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/** 람다 효과. 오래 걸리는 일(DB·Redis)은 Harbor API가 비동기로 처리하므로 여기서는 부르기만 한다. */
public final class LambdaEffect extends Effect {

    private final Spec spec;
    private final Spec.Action action;
    private Expression<?>[] exprs = new Expression<?>[0];

    public LambdaEffect(Spec spec, Spec.Action action) {
        this.spec = spec;
        this.action = action;
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        this.exprs = exprs;
        return true;
    }

    @Override
    protected void execute(Event event) {
        try {
            action.run(event, exprs);
        } catch (RuntimeException e) {
            Skript.warning(spec.label + " 실행 오류: " + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return spec.label;
    }
}
