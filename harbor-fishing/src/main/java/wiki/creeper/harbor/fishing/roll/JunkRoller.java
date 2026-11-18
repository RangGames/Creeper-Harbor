package wiki.creeper.harbor.fishing.roll;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.content.JunkTable;

/** 잡템 판정 (docs 20 3.4). 항목마다 따로 굴려 맞은 것을 모두 돌려준다. 서버 API 없음. */
public final class JunkRoller {

    /**
     * @param items 맞은 잡템
     * @param treasure 보물 지도가 뽑혔는지 (TreasureDropHook 등록 시에만 의미)
     */
    public record Drops(List<JunkTable.Entry> items, boolean treasure) {}

    private JunkRoller() {}

    /**
     * @param treasureBonus treasure_map_chance 스탯 (보물 사냥꾼 +15%·랭크, 배율로 곱함)
     * @param treasureEnabled TreasureDropHook이 등록되어 있는지
     */
    public static Drops roll(
            JunkTable table,
            RegionId region,
            double treasureBonus,
            boolean treasureEnabled,
            RandomGenerator random) {
        List<JunkTable.Entry> hits = new ArrayList<>();
        for (JunkTable.Entry entry : table.entries(region)) {
            if (random.nextDouble() < entry.chance()) {
                hits.add(entry);
            }
        }
        boolean treasure =
                treasureEnabled
                        && table.treasureIn(region)
                        && random.nextDouble()
                                < table.treasureChance() * (1 + Math.max(0, treasureBonus));
        return new Drops(List.copyOf(hits), treasure);
    }
}
