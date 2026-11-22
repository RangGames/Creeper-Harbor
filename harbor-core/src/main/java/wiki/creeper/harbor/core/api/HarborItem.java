package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;

/** Harbor item info read from an ItemStack's custom_data. */
public sealed interface HarborItem permits HarborItem.Plain, HarborItem.Fish {

    /** custom_data format version. Older versions are converted to the new format when read. */
    int version();

    /** Generic item (fishing rod, bait …). */
    record Plain(ItemId id, int version) implements HarborItem {}

    /**
     * Fish item.
     *
     * @param uid unique ID only for ★5, legendary and mythic (recorded in fish_ledger)
     */
    record Fish(
            FishId species,
            double sizeCm,
            Quality quality,
            PlayerRef catcher,
            SeasonId season,
            Instant caughtAt,
            @Nullable UUID uid,
            int version)
            implements HarborItem {}
}
