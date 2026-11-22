package wiki.creeper.harbor.world.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.world.api.UnlockStatus;
import wiki.creeper.harbor.world.api.UnlockStatus.Kind;
import wiki.creeper.harbor.world.api.UnlockStatus.Requirement;
import wiki.creeper.harbor.world.api.WorldFlags;
import wiki.creeper.harbor.world.content.RegionExt;

/**
 * Entry condition checks ('1. design doc' chapter 2, docs 20 8.4 'entry conditions'). Decides from
 * player facts only, without the server API (unit tests).
 *
 * <ul>
 *   <li>Items pass if they are in the inventory or an equipment slot (dropping them after entering
 *       is paid for with environment debuffs)
 *   <li>Quests only look at flags set by Quest ({@link WorldFlags#questDone}; chapter entry flags
 *       are unlock.flags)
 *   <li>Vehicle: with first_entry_only only on the first visit this season, otherwise the given
 *       ship must always be ridden
 *   <li>Reputation is not used as an entry condition (docs 20 8.8)
 * </ul>
 */
public final class UnlockRules {

    private UnlockRules() {}

    /**
     * Player facts.
     *
     * @param ship type of the ship currently ridden (null if none)
     */
    public record Facts(
            int level,
            int jobTier,
            Predicate<String> flag,
            Predicate<ItemId> hasItem,
            boolean visited,
            @Nullable HarborId ship) {}

    /**
     * @param itemName item display name (Dialog text)
     * @param shipName ship display name
     * @param ignoreVehicle ignore the vehicle condition ('highest unlocked region' check)
     */
    public static UnlockStatus check(
            RegionDef region,
            RegionExt ext,
            Facts facts,
            Function<ItemId, String> itemName,
            Function<HarborId, String> shipName,
            boolean ignoreVehicle) {
        List<Requirement> missing = new ArrayList<>();
        RegionDef.Unlock base = region.unlock();
        if (facts.level() < base.level()) {
            missing.add(new Requirement(Kind.LEVEL, "", facts.level(), base.level()));
        }
        if (facts.jobTier() < ext.unlock().jobTier()) {
            missing.add(
                    new Requirement(Kind.JOB_TIER, "", facts.jobTier(), ext.unlock().jobTier()));
        }
        QuestId quest = base.quest();
        if (quest != null) {
            String flag = WorldFlags.questDone(quest);
            if (!facts.flag().test(flag)) {
                missing.add(new Requirement(Kind.QUEST, quest.id().name(), 0, 1));
            }
        }
        for (String flag : ext.unlock().flags()) {
            if (!facts.flag().test(flag)) {
                missing.add(new Requirement(Kind.FLAG, flag, 0, 1));
            }
        }
        for (ItemId item : base.items()) {
            if (!facts.hasItem().test(item)) {
                missing.add(new Requirement(Kind.ITEM, itemName.apply(item), 0, 1));
            }
        }
        List<HarborId> vehicle = ext.unlock().vehicle();
        boolean vehicleNeeded =
                !ignoreVehicle
                        && !vehicle.isEmpty()
                        && !(ext.unlock().firstEntryOnly() && facts.visited());
        HarborId ship = facts.ship();
        if (vehicleNeeded && (ship == null || !vehicle.contains(ship))) {
            missing.add(new Requirement(Kind.VEHICLE, shipName.apply(vehicle.getFirst()), 0, 1));
        }
        return new UnlockStatus(region.id(), missing);
    }
}
