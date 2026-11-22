package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.GiftReaction;

/** Gift given (the item is already removed). */
public final class GiftGivenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcId npc;
    private final @Nullable HarborId item;
    private final GiftReaction reaction;
    private final int points;

    public GiftGivenEvent(
            Player player, NpcId npc, @Nullable HarborId item, GiftReaction reaction, int points) {
        this.player = player;
        this.npc = npc;
        this.item = item;
        this.reaction = reaction;
        this.points = points;
    }

    public Player player() {
        return player;
    }

    public NpcId npc() {
        return npc;
    }

    public @Nullable HarborId item() {
        return item;
    }

    public GiftReaction reaction() {
        return reaction;
    }

    public int points() {
        return points;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
