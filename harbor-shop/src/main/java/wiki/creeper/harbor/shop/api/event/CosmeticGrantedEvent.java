package wiki.creeper.harbor.shop.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * After a cosmetic is newly obtained (after commit, main thread). source = cosmetics_owned.source.
 */
public final class CosmeticGrantedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final HarborId cosmetic;
    private final String source;

    public CosmeticGrantedEvent(UUID player, HarborId cosmetic, String source) {
        this.player = player;
        this.cosmetic = cosmetic;
        this.source = source;
    }

    public UUID player() {
        return player;
    }

    public HarborId cosmetic() {
        return cosmetic;
    }

    public String source() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
