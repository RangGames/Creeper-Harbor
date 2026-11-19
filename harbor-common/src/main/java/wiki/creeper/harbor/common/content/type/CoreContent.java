package wiki.creeper.harbor.common.content.type;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.rig.RigDef;

/**
 * Core가 기본으로 등록하는 콘텐츠 형식
 * (fish·items·regions·balance·messages·rigs·resource_pack·sounds·help·furniture·custom_block). 서버
 * 플러그인과 harbor-tools가 같은 목록을 쓴다.
 */
public final class CoreContent {

    public static final List<ContentType<?>> TYPES =
            List.of(
                    RegionDef.TYPE,
                    ItemDef.TYPE,
                    FishDef.TYPE,
                    Balance.TYPE,
                    Messages.TYPE,
                    RigDef.TYPE,
                    ResourcePackIndex.TYPE,
                    SoundTable.TYPE,
                    HelpBook.TYPE,
                    FurnitureDef.TYPE,
                    CustomBlockDef.TYPE);

    private CoreContent() {}

    /** 형식과 서버 API가 필요 없는 추가 검사를 등록한다. */
    public static void register(ContentLoader loader) {
        TYPES.forEach(loader::register);
        loader.addCheck(FishDef.TYPE, CoreContent::checkFishNumber);
        loader.addCheck(RigDef.TYPE, CoreContent::checkRigParts);
        loader.addCheck(Balance.TYPE, CoreContent::checkRigBalance);
        loader.addCheck(CustomBlockDef.TYPE, CoreContent::checkBlockState);
    }

    /** 커스텀 블록의 노트블록 상태 번호(방향 블록은 state부터 방향 수만큼)는 블록끼리 겹치면 안 된다. */
    private static void checkBlockState(CustomBlockDef block, ContentCheck.Context ctx) {
        for (CustomBlockDef other : ctx.snapshot().all(CustomBlockDef.TYPE).values()) {
            if (other.id().equals(block.id())) {
                continue;
            }
            for (int state : block.states()) {
                if (other.states().contains(state)) {
                    ctx.error(
                            "상태 번호 "
                                    + state
                                    + "가 '"
                                    + other.id()
                                    + "'와 겹칩니다 (방향 블록은 state부터 방향 수만큼 씀)");
                    return;
                }
            }
        }
    }

    /** rig.fps는 20의 약수여야 프레임이 틱에 맞는다. */
    private static void checkRigBalance(Balance balance, ContentCheck.Context ctx) {
        int fps = balance.integer("rig.fps", 10);
        if (fps <= 0 || 20 % fps != 0) {
            ctx.error("rig.fps는 20의 약수(1, 2, 4, 5, 10, 20)여야 합니다: " + fps);
        }
        if (balance.integer("rig.compress.max_gap", 8) < 1) {
            ctx.error("rig.compress.max_gap은 1 이상이어야 합니다");
        }
    }

    /** 리그 하나의 부품·판정 상자 수 상한 (balance.yml rig.max_parts, '8. 시스템 상세 기획' 12장 보스 부위 30개 이하). */
    private static void checkRigParts(RigDef rig, ContentCheck.Context ctx) {
        int max =
                ctx.snapshot().typeNames().contains(Balance.TYPE.name())
                        ? ctx.snapshot().single(Balance.TYPE).integer("rig.max_parts", 30)
                        : 30;
        if (rig.partCount() > max) {
            ctx.error("부품이 " + rig.partCount() + "개로 상한 " + max + "개(rig.max_parts)를 넘습니다");
        }
        if (rig.hitboxCount() > max) {
            ctx.error("판정 상자가 " + rig.hitboxCount() + "개로 상한 " + max + "개(rig.max_parts)를 넘습니다");
        }
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
