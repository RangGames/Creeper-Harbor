package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.npc.api.CutsceneResult;

/** Cutscene ended. Unless result is FINISHED or SKIPPED, Quest does not advance the stage. */
public final class CutsceneEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final HarborId cutscene;
    private final CutsceneResult result;

    public CutsceneEndEvent(Player player, HarborId cutscene, CutsceneResult result) {
        this.player = player;
        this.cutscene = cutscene;
        this.result = result;
    }

    public Player player() {
        return player;
    }

    public HarborId cutscene() {
        return cutscene;
    }

    public CutsceneResult result() {
        return result;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
