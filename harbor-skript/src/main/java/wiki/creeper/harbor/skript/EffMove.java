package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * {@code harbor send %players% to lobby} · {@code harbor send %players% to sea}. Server moves go
 * through the proxy's move procedure (save → lock handoff).
 *
 * <ul>
 *   <li>lobby: to the lobby. Does not set sail automatically on arrival, and gets a slot grace
 *       period if it left the main server
 *   <li>sea: set sail = join the main server queue from the lobby (same as /출항). Does nothing
 *       outside the lobby
 * </ul>
 */
public final class EffMove extends Effect {

    private @Nullable Expression<Player> players;
    private boolean sea;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        sea = parseResult.mark == 1;
        return true;
    }

    @Override
    protected void execute(Event event) {
        Expression<Player> p = players;
        if (p == null) {
            return;
        }
        for (Player player : p.getArray(event)) {
            if (sea) {
                Harbor.api().proxy().requestDepart(player.getUniqueId());
            } else {
                var _ = Harbor.api().proxy().requestLobby(player.getUniqueId(), "skript");
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<Player> p = players;
        return "harbor send "
                + (p == null ? "?" : p.toString(event, debug))
                + (sea ? " to sea" : " to lobby");
    }
}
