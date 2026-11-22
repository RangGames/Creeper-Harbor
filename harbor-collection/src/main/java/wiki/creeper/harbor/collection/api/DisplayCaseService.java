package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Personal aquarium display (docs 20 4.4, 0.1). Tank spots (DisplayAnchor) and edit permission
 * belong to World; display contents, slot count, swim display, and visitor info belong to
 * Collection. Not registered without Harbor-World. Result futures complete on the main thread.
 *
 * <ul>
 *   <li>Display slot count = base + highest cross-season reputation tier bonus (D11, never shrinks)
 *   <li>Theme slot count = base + donor tier extra slots (D14, appearance only. When donation ends,
 *       only the extra slots' themes revert to default)
 * </ul>
 */
public interface DisplayCaseService {

    /** Display slot count (if offline, from the highest reputation tier in the DB). */
    CompletableFuture<Integer> slotCount(UUID owner);

    /** Theme slot count (donor tier is read from Redis). */
    CompletableFuture<Integer> themeSlotCount(UUID owner);

    /** Display slots of the plot (in slot order). */
    CompletableFuture<List<DisplaySlot>> slots(long plotId);

    /**
     * Displays a species in a slot. Checks edit permission (World EDIT_DISPLAY), slot count, and
     * cumulative collection registration, saves immediately, then redraws.
     *
     * @return true if saved
     */
    CompletableFuture<Boolean> set(Player editor, long plotId, int slot, FishId fish);

    /** Changes a slot's theme (null means default). Checks ownership and theme slot count. */
    CompletableFuture<Boolean> setTheme(
            Player editor, long plotId, int slot, @Nullable HarborId theme);

    /** Clears a slot (admin commands call it without an editor). */
    CompletableFuture<Boolean> clear(@Nullable Player editor, long plotId, int slot);
}
