package wiki.creeper.harbor.fishing.roll;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.content.JunkTable;

/** Junk roll (docs 20 3.4). Rolls each entry separately and returns all hits. No server API. */
public final class JunkRoller {

    /**
     * @param items junk hits
     * @param treasure whether a treasure map was rolled (meaningful only with a registered
     *     TreasureDropHook)
     */
    public record Drops(List<JunkTable.Entry> items, boolean treasure) {}

    private JunkRoller() {}

    /**
     * @param treasureBonus treasure_map_chance stat (treasure hunter +15% per rank, applied as a
     *     multiplier)
     * @param treasureEnabled whether a TreasureDropHook is registered
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
