package wiki.creeper.harbor.world.api;

import java.util.List;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Result of a region entry condition check ('1. design doc' chapter 2, docs 20 8.4 'entry
 * conditions'). The push-back Dialog shows progress like "Lv 18 / 20".
 *
 * @param missing unmet conditions (empty means entry is allowed)
 */
public record UnlockStatus(RegionId region, List<Requirement> missing) {

    /** Condition kind. */
    public enum Kind {
        LEVEL,
        JOB_TIER,
        QUEST,
        FLAG,
        ITEM,
        VEHICLE
    }

    /**
     * One unmet condition.
     *
     * @param detail target (item name, flag, ship name …)
     * @param have current value (level or tier, otherwise 0)
     * @param need required value (level or tier, otherwise 1)
     */
    public record Requirement(Kind kind, String detail, int have, int need) {}

    public UnlockStatus {
        missing = List.copyOf(missing);
    }

    public boolean allowed() {
        return missing.isEmpty();
    }

    /** Whether only the vehicle condition is missing (cannot go by airship but can go by ship). */
    public boolean onlyVehicleMissing() {
        return !missing.isEmpty() && missing.stream().allMatch(r -> r.kind() == Kind.VEHICLE);
    }
}
