package wiki.creeper.harbor.economy.api.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 부정 거래 의심이 기록된 뒤 (기록한 서버의 메인 스레드). */
public final class TradeFlaggedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final UUID seller;
    private final String refId;
    private final double score;
    private final List<String> reasons;
    private final boolean held;

    public TradeFlaggedEvent(
            UUID buyer,
            UUID seller,
            String refId,
            double score,
            List<String> reasons,
            boolean held) {
        this.buyer = buyer;
        this.seller = seller;
        this.refId = refId;
        this.score = score;
        this.reasons = reasons;
        this.held = held;
    }

    public UUID buyer() {
        return buyer;
    }

    public UUID seller() {
        return seller;
    }

    public String refId() {
        return refId;
    }

    public double score() {
        return score;
    }

    public List<String> reasons() {
        return reasons;
    }

    public boolean held() {
        return held;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
