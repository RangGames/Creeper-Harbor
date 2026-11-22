package wiki.creeper.harbor.npc.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Harbor reputation (docs 20 5.4). The value is Core player_season.reputation and only this service
 * writes it (Quest, Collection releases and Boss world boss rewards call this API). It resets every
 * season; the best tier reached, kept across seasons, is {@link #bestTier} (D11: housing size and
 * aquarium slots use the best tier, auction slots use the current tier).
 *
 * <p>Call on the main thread. The same refId is counted only once per season (npc_reputation_log
 * unique). Examples: {@code q:<uuid>:<quest>:<cycle>}, {@code boss1:<boss>}.
 */
public interface ReputationService {

    /** 0 if not online. */
    int value(UUID player);

    /** Current tier (1-based). */
    int tier(UUID player);

    /**
     * Best tier reached, kept across seasons (while online). If not online, use {@link
     * #loadBestTier}.
     */
    int bestTier(UUID player);

    /** Reads the best tier reached from the DB (e.g. housing size checks for offline players). */
    CompletableFuture<Integer> loadBestTier(UUID player);

    List<ReputationTier> tiers();

    ReputationTier tierInfo(int tier);

    /** Next tier (empty at the top tier). */
    Optional<ReputationTier> nextTier(UUID player);

    /**
     * Default per source (balance.yml reputation.sources). Quest uses this when its reward table
     * has no value.
     */
    int defaultValue(ReputationSource source);

    /**
     * Adds reputation. CONTRIBUTION sources are multiplied by the reputation_gain modifier, and
     * sources with a daily cap add only up to the cap. On a tier change, fires
     * ReputationTierChangedEvent and saves the best tier immediately.
     *
     * @param refId reference ID for duplicate prevention (null skips the duplicate check)
     */
    CompletableFuture<ReputationResult> add(
            UUID player, int delta, ReputationSource source, @Nullable String refId);

    /** Recent gain history (newest first, while online). */
    List<ReputationLogEntry> recent(UUID player);
}
