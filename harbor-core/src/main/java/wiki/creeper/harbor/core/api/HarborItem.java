package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;

/** ItemStack의 custom_data에서 읽은 Harbor 아이템 정보. */
public sealed interface HarborItem permits HarborItem.Plain, HarborItem.Fish {

    /** custom_data 형식 버전. 옛 버전은 읽을 때 새 형식으로 바꾼다. */
    int version();

    /** 일반 아이템 (낚싯대, 미끼 …). */
    record Plain(ItemId id, int version) implements HarborItem {}

    /**
     * 어종 아이템.
     *
     * @param uid ★5·전설·신화만 있는 고유 ID (fish_ledger에 기록)
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
