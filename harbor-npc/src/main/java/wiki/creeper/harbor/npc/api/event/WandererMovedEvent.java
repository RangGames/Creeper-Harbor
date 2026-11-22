package wiki.creeper.harbor.npc.api.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Wanderer NPC moved, appeared or left (Quest waypoint refresh). visible false means it
 * disappeared.
 */
public final class WandererMovedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final NpcId npc;
    private final @Nullable String candidate;
    private final @Nullable Location location;
    private final boolean visible;

    public WandererMovedEvent(
            NpcId npc, @Nullable String candidate, @Nullable Location location, boolean visible) {
        this.npc = npc;
        this.candidate = candidate;
        this.location = location;
        this.visible = visible;
    }

    public NpcId npc() {
        return npc;
    }

    public @Nullable String candidate() {
        return candidate;
    }

    public @Nullable Location location() {
        return location;
    }

    public boolean visible() {
        return visible;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
