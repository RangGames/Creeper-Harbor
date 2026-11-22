package wiki.creeper.harbor.economy.api.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Quality;

/** After an NPC sale is committed (Quest deliver objectives, etc.). */
public final class FishSoldEvent extends Event {

    /** One sale line. */
    public record Line(FishId fish, Quality quality, double sizeCm, long price) {}

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final List<Line> lines;
    private final long total;

    public FishSoldEvent(UUID player, List<Line> lines, long total) {
        this.player = player;
        this.lines = List.copyOf(lines);
        this.total = total;
    }

    public UUID player() {
        return player;
    }

    public List<Line> lines() {
        return lines;
    }

    public long total() {
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
