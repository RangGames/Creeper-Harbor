package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.jobs.api.ExpSource;

/** 경험치 지급 직전 (감쇠·보정 적용 후). 이벤트 모듈이 양을 바꾸거나 취소할 수 있다. 메인 스레드. */
public final class JobExpGainEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final ExpSource source;
    private double amount;
    private boolean cancelled;

    public JobExpGainEvent(UUID player, ExpSource source, double amount) {
        this.player = player;
        this.source = source;
        this.amount = amount;
    }

    public UUID player() {
        return player;
    }

    public ExpSource source() {
        return source;
    }

    public double amount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = Math.max(0, amount);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
