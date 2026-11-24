package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fish sale earnings lookup SPI for today's catch summary ("4. Fishing presentation" 1.2 "today's
 * catch: count, best grade, earnings", proposed). Economy depends on Fishing, so Economy registers
 * the implementation in the Bukkit ServicesManager and Fishing looks it up; without Economy the
 * summary leaves earnings out.
 */
public interface FishEarnings {

    /**
     * Gold earned by selling fish since the given time (fish sales and big deals, from the ledger).
     * Runs DB I/O off the main thread; the future may complete on any thread.
     */
    CompletableFuture<Long> fishSales(UUID player, Instant since);
}
