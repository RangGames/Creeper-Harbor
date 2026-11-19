package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * {@code harbor server} (이 서버 ID, 예: lobby · play) · {@code harbor server role} (main · lobby ·
 * instance).
 */
public final class ExprServer extends SimpleExpression<String> {

    private boolean role;

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        role = pattern == 1;
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        var infra = Harbor.api().infra();
        return new String[] {role ? infra.role().key() : infra.serverId()};
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return role ? "harbor server role" : "harbor server";
    }
}
