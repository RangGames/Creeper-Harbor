package wiki.creeper.harbor.wildlife.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.GroupDef;
import wiki.creeper.harbor.wildlife.content.WildGroup;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildTemplate;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;

/**
 * species.yml 항목 + 어종 정의 + 리그 + behaviours.yml을 합친 실행용 값 ('17-3' 9.3 "리그·BEHAVE에서 기본값 자동 생성, 디자이너는
 * 덮어쓸 값만"). 변경 불가라 계산 스레드가 그대로 읽는다. 콘텐츠가 리로드되면 새로 만든다.
 *
 * @param animations 리그에 있는 동작 이름 → 길이 (틱)
 * @param threatAction 위협을 받으면 먼저 재생할 놀람 동작 (startle·ink·dart 순, 리그에 있는 것만)
 * @param biteAnimation 입질 동작 (bite, 없으면 고유 동작, 그것도 없으면 null)
 * @param radius 몸 반지름 (블록)
 * @param light 고정 밝기 (−1이면 없음)
 */
public record SpeciesProfile(
        HarborId species,
        FishDef fish,
        WildTemplate template,
        Medium medium,
        WildGroup group,
        GroupDef groupDef,
        @Nullable ActionDef special,
        @Nullable ActionDef threatAction,
        Map<String, ActionDef> actions,
        Map<String, Integer> animations,
        String swimAnimation,
        String idleAnimation,
        @Nullable String biteAnimation,
        double cruiseSpeed,
        double turnRateDeg,
        double pitchLimitDeg,
        double depthMin,
        double depthMax,
        int schoolMin,
        int schoolMax,
        double threatRadius,
        double spawnWeight,
        RigId rig,
        int parts,
        double length,
        double radius,
        double scale,
        int light,
        boolean enabled) {

    /** 놀람 동작 후보 ('17-3' 9.4: startle·ink·dart). */
    static final List<String> THREAT_ORDER = List.of("startle", "ink", "dart");

    public SpeciesProfile {
        actions = Map.copyOf(actions);
        animations = Map.copyOf(animations);
    }

    public boolean hasAnimation(@Nullable String name) {
        return name != null && animations.containsKey(name);
    }

    /** 동작 한 번 길이 (초). 리그에 없으면 fallback. */
    public double animationSeconds(@Nullable String name, double fallback) {
        Integer ticks = name == null ? null : animations.get(name);
        return ticks == null ? fallback : ticks / 20.0;
    }

    /** 기본 속도 (순항 × 성향 배율). */
    public double baseSpeed() {
        return cruiseSpeed * (1 + groupDef.speedBase());
    }

    public boolean naturalSpawn() {
        return enabled
                && spawnWeight > 0
                && fish.grade() != wiki.creeper.harbor.common.model.Grade.LEGENDARY
                && fish.grade() != wiki.creeper.harbor.common.model.Grade.MYTHIC;
    }

    /**
     * 합친다.
     *
     * @param rigDef 그릴 리그 ({@link #rigFor} 결과)
     */
    public static SpeciesProfile resolve(
            WildSpecies s, FishDef fish, RigDef rigDef, BehaviourBook book, WildlifeBalance bal) {
        Optional<ActionDef> special = book.action(s.special());
        WildGroup group =
                s.group() != null
                        ? s.group()
                        : special.map(ActionDef::group).orElse(WildGroup.NORMAL);
        GroupDef groupDef = book.group(group);

        double length =
                rigDef.length() > 0
                        ? rigDef.length() * rigDef.scale() * s.scale()
                        : sizeLength(fish) * s.scale();
        double cruise =
                s.cruiseSpeed() > 0
                        ? s.cruiseSpeed()
                        : Math.clamp(
                                length * bal.cruisePerLength(), bal.cruiseMin(), bal.cruiseMax());
        double turn = s.turnRate() > 0 ? s.turnRate() : bal.turnRate(s.template());
        boolean floor = s.medium() == Medium.FLOOR;
        int schoolMin;
        int schoolMax;
        if (floor) {
            schoolMin = 1;
            schoolMax = 1;
        } else if (s.schoolMin() > 0) {
            schoolMin = s.schoolMin();
            schoolMax = s.schoolMax();
        } else if (length < bal.smallLength()) {
            schoolMin = bal.smallSchoolMin();
            schoolMax = Math.max(schoolMin, bal.smallSchoolMax());
        } else {
            schoolMin = bal.largeSchoolMin();
            schoolMax = Math.max(schoolMin, bal.largeSchoolMax());
        }

        Map<String, Integer> anims = new HashMap<>();
        rigDef.animations().forEach((name, a) -> anims.put(name, a.length()));
        String swim =
                rigDef.defaultAnimation() != null
                        ? rigDef.defaultAnimation()
                        : anims.containsKey("swim") ? "swim" : "";
        String idle = anims.containsKey("idle") ? "idle" : swim;

        ActionDef threat = null;
        if (special.isPresent()
                && special.get().threat()
                && anims.containsKey(special.get().name())) {
            threat = special.get();
        } else {
            for (String name : THREAT_ORDER) {
                Optional<ActionDef> a = book.action(name);
                if (a.isPresent() && anims.containsKey(name)) {
                    threat = a.get();
                    break;
                }
            }
        }
        String bite =
                anims.containsKey("bite")
                        ? "bite"
                        : special.map(ActionDef::name).filter(anims::containsKey).orElse(null);

        return new SpeciesProfile(
                s.id(),
                fish,
                s.template(),
                s.medium(),
                group,
                groupDef,
                special.orElse(null),
                threat,
                book.actions(),
                anims,
                swim,
                idle,
                bite,
                cruise,
                turn,
                bal.pitchLimit(s.template()),
                s.depthMin(),
                s.depthMax(),
                schoolMin,
                schoolMax,
                s.threatRadius() > 0 ? s.threatRadius() : groupDef.threatRadius(),
                s.spawnWeight(),
                rigDef.id(),
                rigDef.partCount(),
                length,
                Math.max(0.25, length / 2),
                s.scale(),
                bal.fixedLight(s.medium()),
                s.enabled());
    }

    /** 리그가 몸길이를 모를 때 어종 크기(cm) 가운데 값을 블록(1m)으로. */
    static double sizeLength(FishDef fish) {
        double cm = (fish.sizeMin() + fish.sizeMax()) / 2;
        return cm > 0 ? cm / 100.0 : 0.4;
    }

    /**
     * 그릴 리그를 고른다: species.yml rig → rig/fish/&lt;어종 이름&gt; (Fishing 그림자와 같은 규칙) →
     * rig/wild/&lt;템플릿&gt;.
     */
    public static Optional<RigId> rigFor(WildSpecies s, FishDef fish, Set<RigId> known) {
        RigId explicit = s.rig();
        if (explicit != null) {
            return known.contains(explicit) ? Optional.of(explicit) : Optional.empty();
        }
        HarborId fid = fish.id().id();
        RigId own = new RigId(new HarborId(fid.namespace(), "rig/fish/" + fid.name()));
        if (known.contains(own)) {
            return Optional.of(own);
        }
        RigId template =
                new RigId(
                        new HarborId(HarborId.DEFAULT_NAMESPACE, "rig/wild/" + s.template().key()));
        return known.contains(template) ? Optional.of(template) : Optional.empty();
    }
}
