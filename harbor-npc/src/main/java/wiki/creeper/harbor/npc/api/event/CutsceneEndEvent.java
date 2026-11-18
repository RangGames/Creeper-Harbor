package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.npc.api.CutsceneResult;

/** 컷씬 종료. result가 FINISHED·SKIPPED가 아니면 Quest는 단계를 넘기지 않는다. */
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
