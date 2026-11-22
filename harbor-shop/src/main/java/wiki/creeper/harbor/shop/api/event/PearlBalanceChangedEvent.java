package wiki.creeper.harbor.shop.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.ledger.LedgerReason;

/** After the pearl balance changes (after commit, main thread). For scoreboard and GUI updates. */
public final class PearlBalanceChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final long balance;
    private final long paidBalance;
    private final long delta;
    private final LedgerReason reason;

    public PearlBalanceChangedEvent(
            UUID player, long balance, long paidBalance, long delta, LedgerReason reason) {
        this.player = player;
        this.balance = balance;
        this.paidBalance = paidBalance;
        this.delta = delta;
        this.reason = reason;
    }

    public UUID player() {
        return player;
    }

    public long balance() {
        return balance;
    }

    public long paidBalance() {
        return paidBalance;
    }

    public long delta() {
        return delta;
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
