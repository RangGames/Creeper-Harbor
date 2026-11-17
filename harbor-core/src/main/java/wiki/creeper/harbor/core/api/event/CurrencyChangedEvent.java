package wiki.creeper.harbor.core.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;

/** 재화 변경이 커밋된 뒤 메인 스레드에서 발생한다. 플레이어가 오프라인일 수도 있다. */
public final class CurrencyChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final Currency currency;
    private final long delta;
    private final long balance;
    private final LedgerReason reason;

    public CurrencyChangedEvent(
            UUID player, Currency currency, long delta, long balance, LedgerReason reason) {
        this.player = player;
        this.currency = currency;
        this.delta = delta;
        this.balance = balance;
        this.reason = reason;
    }

    public UUID player() {
        return player;
    }

    public Currency currency() {
        return currency;
    }

    public long delta() {
        return delta;
    }

    public long balance() {
        return balance;
    }

    public LedgerReason reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
