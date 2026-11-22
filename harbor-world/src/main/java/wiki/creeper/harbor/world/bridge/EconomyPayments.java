package wiki.creeper.harbor.world.bridge;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.economy.api.RepairService;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.world.WorldKit;

/**
 * Payments via Economy ShopService.charge and RepairService (this class is loaded only when
 * Harbor-Economy is enabled).
 */
final class EconomyPayments implements Payments {

    private final WorldKit kit;
    private final Payments fallback;

    EconomyPayments(WorldKit kit, Payments fallback) {
        this.kit = kit;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra) {
        Optional<ShopService> shop = kit.api().service(ShopService.class);
        if (shop.isEmpty() || price <= 0) {
            return fallback.charge(player, price, reason, refId, extra);
        }
        return shop.get().charge(player, price, reason, refId, extra);
    }

    @Override
    public long repairQuote(UUID player, long price, boolean broken) {
        if (price <= 0) {
            return 0;
        }
        return kit.api()
                .service(RepairService.class)
                .map(r -> r.quote(player, price, broken))
                .orElseGet(() -> fallback.repairQuote(player, price, broken));
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> repair(
            Player player, long cost, String refId, @Nullable SqlConsumer extra) {
        Optional<RepairService> repair = kit.api().service(RepairService.class);
        if (repair.isEmpty() || cost <= 0) {
            return fallback.repair(player, cost, refId, extra);
        }
        return repair.get().pay(player.getUniqueId(), cost, refId, extra);
    }
}
