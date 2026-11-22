package wiki.creeper.harbor.skript.bridge;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A tension minigame started by a script ({@code start harbor tension}) ended. Fired by
 * Harbor-Skript on the main thread when the Fishing TensionSession reports its result.
 */
public final class ScriptTensionEndEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String result;
    private final String tag;

    public ScriptTensionEndEvent(Player player, String result, String tag) {
        super(player);
        this.result = result;
        this.tag = tag;
    }

    /** caught · line_broke · escaped · aborted. */
    public String result() {
        return result;
    }

    /** Tag given when starting (empty if none). */
    public String tag() {
        return tag;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
