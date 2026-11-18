package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 진실의 조각 획득. owned·total은 획득 후 모은 수와 전체 수. 메인 스레드에서 발생한다. */
public final class ChroniclePieceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String piece;
    private final int owned;
    private final int total;

    public ChroniclePieceEvent(UUID player, String piece, int owned, int total) {
        this.player = player;
        this.piece = piece;
        this.owned = owned;
        this.total = total;
    }

    public UUID player() {
        return player;
    }

    public String piece() {
        return piece;
    }

    public int owned() {
        return owned;
    }

    public int total() {
        return total;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
