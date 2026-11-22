package wiki.creeper.harbor.npc.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Cutscene player (docs 20 5.4). Used by Quest (story cutscenes), Jobs (job change cutscenes) and
 * Boss (climax sequences). Cutscenes are content/cutscenes/*.yml with IDs {@code
 * harbor:cutscene/<name>}. While playing, server moves are refused (HandoffBlocker "cutscene") and
 * damage and hunger are blocked. Restore info is kept in cutscene_restore so players are not left
 * in spectator mode after quitting or a forced shutdown.
 *
 * <pre>{@code
 * cutscenes.play(player, HarborId.parse("cutscene/prologue_drift"))
 *         .thenAccept(result -> { if (result.completed()) advance(player); });
 * }</pre>
 *
 * The result future completes on the main thread.
 */
public interface CutsceneService {

    CompletableFuture<CutsceneResult> play(Player player, HarborId cutscene);

    /**
     * Starts for several players on the same tick (an independent instance per player, for the
     * climax plaza sequence).
     */
    Map<UUID, CompletableFuture<CutsceneResult>> playGroup(
            Collection<? extends Player> players, HarborId cutscene);

    boolean isPlaying(Player player);

    Optional<HarborId> playing(Player player);

    /** Player flag cutscene/<name> (permanent). */
    boolean hasSeen(Player player, HarborId cutscene);

    /** If playing, ends it as CANCELLED. */
    boolean stop(Player player);

    boolean exists(HarborId cutscene);
}
