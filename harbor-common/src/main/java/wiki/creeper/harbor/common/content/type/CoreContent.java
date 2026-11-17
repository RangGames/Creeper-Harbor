package wiki.creeper.harbor.common.content.type;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentType;

/**
 * Core가 기본으로 등록하는 콘텐츠 형식 (fish·items·regions·balance·messages). 서버 플러그인과 harbor-tools가 같은
 * 목록을 쓴다.
 */
public final class CoreContent {

    public static final List<ContentType<?>> TYPES =
            List.of(RegionDef.TYPE, ItemDef.TYPE, FishDef.TYPE, Balance.TYPE, Messages.TYPE);

    private CoreContent() {}

    /** 형식과 서버 API가 필요 없는 추가 검사를 등록한다. */
    public static void register(ContentLoader loader) {
        TYPES.forEach(loader::register);
        loader.addCheck(FishDef.TYPE, CoreContent::checkFishNumber);
    }

    /** 도감 번호(no)는 어종마다 달라야 한다. */
    private static void checkFishNumber(FishDef fish, ContentCheck.Context ctx) {
        if (fish.no() < 1) {
            ctx.error("도감 번호 no는 1 이상이어야 합니다");
            return;
        }
        for (FishDef other : ctx.snapshot().all(FishDef.TYPE).values()) {
            if (other.no() == fish.no() && !other.id().equals(fish.id())) {
                ctx.error("도감 번호 " + fish.no() + "가 '" + other.id() + "'와 중복됩니다");
            }
        }
    }
}
