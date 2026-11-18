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
 * 진입 조건 판정 ('1. 기획서 본문' 2장, docs 20 8.4 '진입 조건'). 서버 API 없이 플레이어 사실만 받아 판정한다 (단위 테스트).
 *
 * <ul>
 *   <li>아이템은 인벤토리나 장비 칸에 있으면 통과 (들어간 뒤 버리는 것은 환경 디버프로 대가를 치른다)
 *   <li>퀘스트는 Quest가 세운 플래그만 본다 ({@link WorldFlags#questDone}, 장 진입 플래그는 unlock.flags)
 *   <li>탈것: first_entry_only면 이번 시즌 첫 방문에만, 아니면 늘 지정 선박에 타고 있어야 한다
 *   <li>평판은 진입 조건으로 쓰지 않는다 (docs 20 8.8)
 * </ul>
 */
public final class UnlockRules {

    private UnlockRules() {}

    /**
     * 플레이어 사실.
     *
     * @param ship 지금 타고 있는 선박 종류 (없으면 null)
     */
    public record Facts(
            int level,
            int jobTier,
            Predicate<String> flag,
            Predicate<ItemId> hasItem,
            boolean visited,
            @Nullable HarborId ship) {}

    /**
     * @param itemName 아이템 표시 이름 (Dialog 문구)
     * @param shipName 선박 표시 이름
     * @param ignoreVehicle 탈것 조건을 보지 않는다 ('해금한 가장 높은 지역' 판정)
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
