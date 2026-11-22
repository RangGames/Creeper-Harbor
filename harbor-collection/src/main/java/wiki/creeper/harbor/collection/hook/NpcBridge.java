package wiki.creeper.harbor.collection.hook;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Facade hiding the optional NPC dependency. Without Harbor-Npc it is {@link #NONE} (highest
 * reputation tier 1, no reputation grants). NPC api types are not in the signatures, so this
 * interface can be loaded even without NPC.
 */
public interface NpcBridge {

    /** Highest cross-season reputation tier reached by an online player (D11). */
    int bestTier(UUID player);

    /** Including offline players (DB). The result completes on the main thread. */
    CompletableFuture<Integer> loadBestTier(UUID player);

    /**
     * Release reputation (source RELEASE, CONTRIBUTION · daily cap applied by NPC).
     *
     * @param amount 0 means the NPC default (balance.yml reputation.sources.release)
     * @return amount actually added (0 if offline or capped)
     */
    CompletableFuture<Integer> addReleaseReputation(UUID player, int amount, String refId);

    /** Callback for reputation tier changes (recomputes personal aquarium slots). */
    default void onTierChanged(Consumer<UUID> listener) {}

    NpcBridge NONE =
            new NpcBridge() {
                @Override
                public int bestTier(UUID player) {
                    return 1;
                }

                @Override
                public CompletableFuture<Integer> loadBestTier(UUID player) {
                    return CompletableFuture.completedFuture(1);
                }

                @Override
                public CompletableFuture<Integer> addReleaseReputation(
                        UUID player, int amount, String refId) {
                    return CompletableFuture.completedFuture(0);
                }
            };
}
