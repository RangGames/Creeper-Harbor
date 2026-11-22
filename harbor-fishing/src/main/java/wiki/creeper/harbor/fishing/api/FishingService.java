package wiki.creeper.harbor.fishing.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Harbor-Fishing public API (docs 20 3.2). Look it up in the ServicesManager. Use on the main
 * thread.
 *
 * <p>Extension points (Fishing only calls registered implementations and does not depend on their
 * modules):
 *
 * <ul>
 *   <li>{@link CodexProvider}: Collection (codex registration status)
 *   <li>{@link ShadowDriver}: Wildlife (shadow takeover while waiting, claimShadow in
 *       FishingCastEvent)
 *   <li>{@link RegionKeyResolver}, {@link TreasureDropHook}: World (region key, treasure maps)
 *   <li>{@link CatchPresentationOverride}: Shop (catch presentation cosmetics)
 *   <li>{@link #openTension}, {@link #createCatch}: Boss (anchor tension, guaranteed boss species
 *       catch)
 *   <li>{@link SeaEventMessage}: fish_effects that World sends over harbor:event
 * </ul>
 */
public interface FishingService {

    SessionState session(UUID player);

    boolean isFishing(UUID player);

    /** Legendary pity counter (number of casts that met the conditions). */
    int pity(UUID player, FishId fish);

    /** Number of big fish lost to the job quality cap (this session). */
    int missedBigCount(UUID player);

    /** Registers the codex lookup SPI (Collection). */
    void registerCodex(CodexProvider provider);

    /** Registers the region key extension point (World). Calling again replaces it. */
    void registerRegionResolver(RegionKeyResolver resolver);

    /** Registers the treasure map drop extension point (World). */
    void registerTreasureHook(TreasureDropHook hook);

    /** Registers a catch presentation cosmetic (Shop). */
    void registerPresentation(CatchPresentationOverride override);

    /**
     * Takes over the shadow of a waiting cast (Wildlife). Accepted only in the WAITING state; false
     * if another driver already has it. Normally use {@code FishingCastEvent#claimShadow}.
     */
    boolean claimShadow(UUID player, ShadowDriver driver);

    /** Traps. */
    TrapService traps();

    /**
     * Monte Carlo roll (virtual thread). Rolls count times under the base conditions without
     * modifiers and returns the per-grade ratios and the average number of casts until the first
     * legendary catch.
     */
    CompletableFuture<SimulationResult> simulate(RegionId region, int count);

    /**
     * Expected spawn ratios per region (proposed, for the Economy price S₀). Computed from the base
     * weights without modifiers; species that need bait or an event, and legendaries and mythics,
     * are 0. Time and weather conditions are ignored (close to a daily average).
     */
    double expectedShare(RegionId region, FishId fish);

    /**
     * Opens a tension minigame (proposed, Boss anchor). If the player is fishing, that session is
     * aborted (ABORTED) first. Empty if a tension session already exists.
     */
    Optional<TensionSession> openTension(Player player, TensionSpec spec);

    /**
     * Creates a boss species item (proposed, Boss guaranteed catch + quality bonus). Size and
     * quality rolls, the job cap, QualityRolledEvent and the ★5/legendary/mythic ledger records are
     * handled as for a normal catch. EXP, bait and durability are not handled, and the caller hands
     * out the item. Empty for an unknown species.
     */
    Optional<CatchResult> createCatch(Player player, FishId fish, CatchOptions options);

    /** IDs of active sea events (received over harbor:event). */
    Set<String> activeSeaEvents();

    /** Simulation result. */
    record SimulationResult(
            int casts,
            Map<Grade, Integer> grades,
            Map<FishId, Integer> species,
            double legendAverageCasts) {}
}
