package wiki.creeper.harbor.quest.logic;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * 목표 판정에 쓰는 아이템·포획 정보 (서버 API와 분리해 단위 테스트로 검사한다).
 *
 * @param fish 어종 (어종 아이템이 아니면 null)
 * @param item 일반 아이템 ID (어종이면 null)
 * @param region 포획 지역 (catch), 어종 출현 지역 (deliver·donate)
 * @param trap 통발 수확인지
 */
public record CatchFacts(
        @Nullable FishId fish,
        @Nullable ItemId item,
        @Nullable Grade grade,
        @Nullable RegionId region,
        int quality,
        double size,
        boolean trap) {

    public static CatchFacts fish(
            FishId fish,
            Grade grade,
            @Nullable RegionId region,
            int quality,
            double size,
            boolean trap) {
        return new CatchFacts(fish, null, grade, region, quality, size, trap);
    }

    public static CatchFacts item(ItemId item) {
        return new CatchFacts(null, item, null, null, 0, 0, false);
    }
}
