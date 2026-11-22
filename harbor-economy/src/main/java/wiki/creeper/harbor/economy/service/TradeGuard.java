package wiki.creeper.harbor.economy.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HandoffBlocker;

/**
 * In-trade marker (docs 20 7.3 EconomyState "in-trade marker", 7.4 edge cases). Allows only one
 * trade per player at a time (sell, buy, auction listing, auction purchase, craft, mail claim) and,
 * while one is in progress, defers server moves until it finishes (Core HandoffBlocker Delay). If
 * it does not finish within the time limit, Core cancels the move. When the trade ends, the
 * inventory is saved soon (PlayerService.saveSoon).
 */
public final class TradeGuard implements HandoffBlocker {

    /**
     * One in-progress trade. {@link #done()} must be called (calling it more than once is fine).
     */
    public static final class Ticket {

        private final TradeGuard guard;
        private final UUID player;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private Ticket(TradeGuard guard, UUID player) {
            this.guard = guard;
            this.player = player;
        }

        public void done() {
            if (guard.inFlight.remove(player, this)) {
                // The trade result (currency, auction, mail rows) is already committed: save the
                // inventory soon too so it stays consistent even if the server dies
                guard.afterTrade.accept(player);
            }
            future.complete(null);
        }

        /**
         * Clears the marker when the future finishes (success or failure). The returned future
         * completes after the marker is cleared: returning the original future could let the
         * caller's follow-up stages run first (later-attached stages run first), so the next trade
         * was rejected as "in progress" (claim all only claimed one mail).
         */
        public <T> CompletableFuture<T> track(CompletableFuture<T> work) {
            return work.whenComplete((r, e) -> done());
        }
    }

    private final Map<UUID, Ticket> inFlight = new ConcurrentHashMap<>();
    private final Consumer<UUID> afterTrade;
    private final Predicate<UUID> frozen;

    /**
     * @param afterTrade called when a trade ends (success or failure, any thread). Usually {@code
     *     PlayerService::saveSoon}
     * @param frozen whether the player's server-move snapshot was taken (main thread). Usually
     *     {@code PlayerService::frozen}
     */
    public TradeGuard(Consumer<UUID> afterTrade, Predicate<UUID> frozen) {
        this.afterTrade = afterTrade;
        this.frozen = frozen;
    }

    /** Starts a trade. Empty if a trade is already in progress. */
    public Optional<Ticket> begin(UUID player) {
        // A trade started after the server-move snapshot moves items outside the snapshot and
        // duplicates them
        if (frozen.test(player)) {
            return Optional.empty();
        }
        Ticket ticket = new Ticket(this, player);
        return inFlight.putIfAbsent(player, ticket) == null
                ? Optional.of(ticket)
                : Optional.empty();
    }

    public boolean busy(UUID player) {
        return inFlight.containsKey(player);
    }

    @Override
    public Verdict check(Player player, String destination) {
        Ticket ticket = inFlight.get(player.getUniqueId());
        return ticket == null ? ALLOW : new Delay(ticket.future);
    }
}
