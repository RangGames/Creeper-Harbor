package wiki.creeper.harbor.core.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * Pre-transfer hook (docs 20 0.2). When the source server receives the proxy's transfer prepare
 * request, it calls every registered hook on the main thread before saving. If any refuses, the
 * transfer is cancelled; if any delays, saving proceeds once all of them finish. If they don't
 * finish within {@link #MAX_DELAY_MILLIS}, the transfer is cancelled (a trade granting or taking
 * items after the snapshot is taken would duplicate or lose them). Combines the Collection
 * pending-work hook, the NPC transfer-refusal hook (during cutscenes), and the Economy delay while
 * a trade or donation is in progress.
 */
@FunctionalInterface
public interface HandoffBlocker {

    /**
     * Maximum delay. Set to 4 seconds so saving finishes within the proxy's transfer prepare limit
     * (5 seconds) (proposed value).
     */
    long MAX_DELAY_MILLIS = 4_000;

    sealed interface Verdict permits Allow, Deny, Delay {}

    record Allow() implements Verdict {}

    /**
     * @param reason reason code returned to the proxy (e.g. cutscene). The message key is {@code
     *     core.handoff.blocked.<reason>}
     */
    record Deny(String reason) implements Verdict {}

    /**
     * Future that completes when the in-progress work is done. The transfer proceeds even if it
     * fails.
     */
    record Delay(CompletableFuture<?> until) implements Verdict {}

    Verdict ALLOW = new Allow();

    Verdict check(Player player, String destination);
}
