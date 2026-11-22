package wiki.creeper.harbor.collection.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * One personal aquarium display slot (housing_display). It displays the cumulative codex record
 * rather than an item, so it persists across seasons.
 *
 * @param slot tank spot number (World DisplayAnchor.slot)
 * @param size cumulative max size at the time of display
 * @param quality cumulative best quality at the time of display (proposed column)
 * @param firstCaught first catch time (proposed column)
 * @param theme aquarium theme of this slot (null means default theme; D14: only within the theme
 *     slot count)
 */
public record DisplaySlot(
        int slot,
        FishId fish,
        double size,
        int quality,
        Instant firstCaught,
        @Nullable HarborId theme) {}
