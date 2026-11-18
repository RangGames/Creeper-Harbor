package wiki.creeper.harbor.quest.logic;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.quest.content.CatchFilter;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.Target;

/** 목표 대상 비교 (docs 20 6.4 목표 추적). 순수 계산. */
public final class Matchers {

    private Matchers() {}

    /** catch·donate·deliver: 어종·등급·지역 대상과 품질·크기·지역·출처 조건. */
    public static boolean matchesFish(Objective o, CatchFacts facts) {
        if (facts.trap() && o.source() != CatchFilter.ANY) {
            return false;
        }
        if (!matchesTarget(o.target(), facts)) {
            return false;
        }
        var q = o.qualityMin();
        if (q != null && facts.quality() < q.stars()) {
            return false;
        }
        if (o.sizeMin() > 0 && facts.size() < o.sizeMin()) {
            return false;
        }
        var region = o.region();
        return region == null || region.equals(facts.region());
    }

    static boolean matchesTarget(Target target, CatchFacts facts) {
        return switch (target.kind()) {
            case ANY -> facts.fish() != null || facts.item() != null;
            case ID -> {
                HarborId id = target.id();
                var fish = facts.fish();
                var item = facts.item();
                yield id != null
                        && ((fish != null && fish.id().equals(id))
                                || (item != null && item.id().equals(id)));
            }
            case GRADE -> {
                Grade want = target.grade();
                Grade have = facts.grade();
                yield want != null
                        && have != null
                        && facts.fish() != null
                        && (target.orAbove() ? have.isAtLeast(want) : have == want);
            }
            case REGION -> {
                HarborId id = target.id();
                var region = facts.region();
                yield id != null
                        && region != null
                        && region.id().equals(id)
                        && facts.fish() != null;
            }
            case POINT, NAME -> false;
        };
    }

    /** talk·boss·cutscene·use_item·affinity: ID 하나를 비교 (any면 모두). */
    public static boolean matchesId(Objective o, @Nullable HarborId id) {
        Target t = o.target();
        if (t.kind() == Target.Kind.ANY) {
            return true;
        }
        HarborId want = t.id();
        return want != null && want.equals(id);
    }

    /** visit: 지역 진입 또는 지점 반경. */
    public static boolean matchesVisit(
            Objective o, @Nullable HarborId region, String world, double x, double y, double z) {
        Target t = o.target();
        return switch (t.kind()) {
            case REGION -> {
                HarborId want = t.id();
                yield want != null && want.equals(region);
            }
            case POINT -> {
                Target.Point p = t.point();
                yield p != null && p.contains(world, x, y, z);
            }
            default -> false;
        };
    }

    /** 다른 모듈 신호 (ObjectiveSignal.signal): 표기 그대로 비교하거나 any. */
    public static boolean matchesRaw(Objective o, String target) {
        Target t = o.target();
        if (t.kind() == Target.Kind.ANY || target.equals("any")) {
            return true;
        }
        if (t.raw().equals(target)) {
            return true;
        }
        HarborId want = t.id();
        return want != null && HarborId.tryParse(target).map(want::equals).orElse(false);
    }
}
