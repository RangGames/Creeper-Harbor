package wiki.creeper.harbor.fishing.roll;

import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Spawn roll context (docs 20 3.4 step 1).
 *
 * @param time dawn · day · dusk · night
 * @param weather clear · rain · thunder
 * @param moon full · new · (empty string otherwise)
 * @param serverFlags server progress flag check (unlock_flag)
 * @param caught collection registration status (unregistered weight)
 * @param areas sub-areas at the bobber location (RegionKeyResolver)
 * @param areasKnown whether sub-areas are known. If false (World not registered), species area
 *     conditions are not checked
 * @param mythic open mythic windows (sea events this player participates in)
 */
public record RollContext(
        RegionId region,
        String time,
        String weather,
        String moon,
        @Nullable ItemId bait,
        int jobTier,
        Predicate<String> serverFlags,
        Predicate<FishId> caught,
        List<String> areas,
        boolean areasKnown,
        List<MythicWindow> mythic) {

    public RollContext {
        areas = List.copyOf(areas);
        mythic = List.copyOf(mythic);
    }

    /**
     * One mythic window (mythic in a sea event's fish_effects).
     *
     * @param chance chance per cast (before adding mythic_chance from mythic premonition)
     * @param cap server-wide maximum count per event (FishingManager does the reservation)
     */
    public record MythicWindow(String eventId, FishDef fish, double chance, int cap) {}
}
