package wiki.creeper.harbor.boss.content;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.rig.RigDef;

/** Boss 콘텐츠 형식과 서버 API 없는 검사. 플러그인과 harbor-tools(CI)가 같은 목록을 등록한다. */
public final class BossContent {

    public static final List<ContentType<?>> TYPES = List.of(BossDef.TYPE);

    /**
     * 리그 검사 (docs 20 10.3 참조 검사): 판정 부위 뼈가 리그에 있고 hitbox가 있는지, 리그 부위 수가 30개 이하인지, 약점 부위가 발광할 부품을
     * 가졌는지. 리그 존재 자체는 참조 검사(ctx.ref)가 본다.
     */
    public static final ContentCheck<BossDef> RIG_CHECK =
            (boss, ctx) -> {
                Optional<RigDef> rig = ctx.snapshot().get(RigDef.TYPE, boss.rig().id());
                if (rig.isEmpty()) {
                    return;
                }
                RigDef def = rig.get();
                int total = def.partCount() + def.hitboxCount();
                if (total > BossDef.MAX_RIG_PARTS) {
                    ctx.error("보스 리그 부위(표시 + 판정)가 " + BossDef.MAX_RIG_PARTS + "개를 넘습니다: " + total);
                }
                Set<String> hitBones = new HashSet<>();
                Set<String> partBones = new HashSet<>();
                for (RigDef.Bone bone : def.bones()) {
                    if (bone.hitbox() != null) {
                        hitBones.add(bone.name());
                    }
                    if (bone.part() != null) {
                        partBones.add(bone.name());
                    }
                }
                for (BossDef.Part part : boss.parts()) {
                    if (!hitBones.contains(part.bone())) {
                        ctx.error(
                                "부위 "
                                        + part.id()
                                        + "의 뼈 "
                                        + part.bone()
                                        + "에 리그 hitbox가 없습니다 ("
                                        + def.id()
                                        + ")");
                    }
                    if (part.weakpoint() && !partBones.contains(part.bone())) {
                        ctx.error("약점 부위 " + part.id() + "의 뼈에 발광할 표시 부품이 없습니다");
                    }
                }
                for (BossDef.PatternDef pattern : boss.patterns()) {
                    String anim = pattern.animation();
                    if (anim != null && !def.animations().containsKey(anim)) {
                        ctx.error("패턴 " + pattern.id() + "의 동작 " + anim + "이(가) 리그에 없습니다");
                    }
                }
                String intro = boss.presentation().intro();
                if (intro != null && !def.animations().containsKey(intro)) {
                    ctx.error("등장 동작 " + intro + "이(가) 리그에 없습니다");
                }
            };

    /** 보스 어종 검사: calm 보스는 release_only 어종, capture 보스는 아닌 어종. */
    public static final ContentCheck<BossDef> FISH_CHECK =
            (boss, ctx) -> {
                var fishId = boss.fish();
                if (fishId == null) {
                    return;
                }
                Optional<FishDef> fish = ctx.snapshot().get(FishDef.TYPE, fishId.id());
                if (fish.isEmpty()) {
                    return;
                }
                boolean release = fish.get().releaseOnly();
                if (boss.goal() == BossDef.Goal.CALM && !release) {
                    ctx.error("calm 보스의 어종은 release_only여야 합니다: " + fishId);
                }
            };

    private BossContent() {}
}
