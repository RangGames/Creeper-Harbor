package wiki.creeper.harbor.quest.logic;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.quest.content.CatchFilter;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.Target;

/** Objective target comparison (docs 20 6.4 objective tracking). Pure computation. */
public final class Matchers {

    private Matchers() {}

    /**
     * catch, donate, deliver: species, grade and region targets plus quality, size, region and
     * source conditions.
     */
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

    /** talk, boss, cutscene, use_item, affinity: compares a single ID (any matches all). */
    public static boolean matchesId(Objective o, @Nullable HarborId id) {
        Target t = o.target();
        if (t.kind() == Target.Kind.ANY) {
            return true;
        }
        HarborId want = t.id();
        return want != null && want.equals(id);
    }

    /** visit: entering a region or the radius of a point. */
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

    /** Signals from other modules (ObjectiveSignal.signal): compares the notation as is, or any. */
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
