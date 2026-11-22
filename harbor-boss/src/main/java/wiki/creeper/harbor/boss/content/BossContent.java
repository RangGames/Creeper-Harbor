package wiki.creeper.harbor.boss.content;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.rig.RigDef;

/**
 * Boss content types and checks that need no server API. The plugin and harbor-tools (CI) register
 * the same list.
 */
public final class BossContent {

    public static final List<ContentType<?>> TYPES = List.of(BossDef.TYPE);

    /**
     * Rig check (docs 20 10.3 reference checks): hit part bones exist in the rig and have a hitbox,
     * the rig has at most 30 parts, and weak point parts have a piece that can glow. Rig existence
     * itself is covered by the reference check (ctx.ref).
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

    /**
     * Boss fish check: calm bosses need a release_only fish, capture bosses a non-release_only one.
     */
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
