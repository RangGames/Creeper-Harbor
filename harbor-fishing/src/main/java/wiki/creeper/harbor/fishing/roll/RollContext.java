package wiki.creeper.harbor.fishing.roll;

import java.util.List;
import java.util.Set;
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
 * @param playerFlags player flag check (species extra player_flag, proposed)
 * @param equipment tags of the worn equipment (species extra equipment_tag, proposed: gold, iron …
 *     from the armour material, plus Harbor item IDs)
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
        List<MythicWindow> mythic,
        Predicate<String> playerFlags,
        Set<String> equipment) {

    public RollContext {
        areas = List.copyOf(areas);
        mythic = List.copyOf(mythic);
        equipment = Set.copyOf(equipment);
    }

    /** The same context without mythic windows (normal reroll when a reservation is late). */
    public RollContext withoutMythic() {
        return new RollContext(
                region,
                time,
                weather,
                moon,
                bait,
                jobTier,
                serverFlags,
                caught,
                areas,
                areasKnown,
                List.of(),
                playerFlags,
                equipment);
    }

    /**
     * Cast-level condition tokens for conditional bait and part bonuses: the time key and the
     * region name (e.g. night, frost_fjord).
     */
    public Set<String> castTokens() {
        return Set.of(time, region.id().name());
    }

    /**
     * One mythic window (mythic in a sea event's fish_effects).
     *
     * @param chance chance per cast (before adding mythic_chance from mythic premonition)
     * @param cap server-wide maximum count per event (FishingManager does the reservation)
     */
    public record MythicWindow(String eventId, FishDef fish, double chance, int cap) {}
}
