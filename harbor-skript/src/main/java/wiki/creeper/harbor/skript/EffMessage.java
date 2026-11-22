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
 * {@code send harbor message %string% to %players%}: the text of a messages_ko.yml key
 * (MiniMessage, including glyphs).
 */
public final class EffMessage extends Effect {

    private @Nullable Expression<String> key;
    private @Nullable Expression<Player> players;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        key = (Expression<String>) exprs[0];
        players = (Expression<Player>) exprs[1];
        return true;
    }

    @Override
    protected void execute(Event event) {
        Expression<String> k = key;
        Expression<Player> p = players;
        String raw = k == null ? null : k.getSingle(event);
        if (raw == null || p == null) {
            return;
        }
        for (Player player : p.getArray(event)) {
            Harbor.api().messages().send(player, raw);
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<String> k = key;
        Expression<Player> p = players;
        return "send harbor message "
                + (k == null ? "?" : k.toString(event, debug))
                + " to "
                + (p == null ? "?" : p.toString(event, debug));
    }
}
