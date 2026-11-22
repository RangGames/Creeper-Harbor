package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Codex title key rules (docs 20 4.4). Title keys are the names in codex.yml as-is (codex_25,
 * codex_region/harbor), and Season TitleId.of adds the prefix (harbor:title/codex_25). Uses no
 * server API, so unit tests and harbor-tools share it.
 */
public final class CodexTitles {

    /** Max length of a Season TitleService reference ID. */
    public static final int REF_MAX = 64;

    private CodexTitles() {}

    /** Region completion title key: prefix/region name (e.g. codex_region/harbor). */
    public static String regionTitle(String prefix, RegionId region) {
        return prefix + "/" + region.id().name();
    }

    /**
     * Title key → full title ID (same rule as Season TitleId.of: default namespace harbor, title/
     * prefix on the path).
     */
    public static HarborId titleId(String key) {
        HarborId id = HarborId.parse(key);
        return id.hasPrefix("title/") ? id : new HarborId(id.namespace(), "title/" + id.path());
    }

    /**
     * Grant reference ID {@code codex:<season>:<key>} (truncated beyond 64 chars; titles are unique
     * per (uuid, title), so the reference ID is for records only).
     */
    public static String refId(int season, String key) {
        String ref = "codex:" + season + ":" + key;
        return ref.length() <= REF_MAX ? ref : ref.substring(0, REF_MAX);
    }

    /**
     * Title keys the player should have given the current codex state (for the pending queue and
     * achievements from before Season existed). Cumulative completion stages, and regions completed
     * in the season codex.
     *
     * @param totalPercent cumulative codex completion (0~100)
     * @param regionFish denominator per region (excluding mythic)
     * @param seasonRegistered species registered in the season codex
     */
    public static List<String> earned(
            CodexRules rules,
            double totalPercent,
            Map<RegionId, Set<FishId>> regionFish,
            Set<FishId> seasonRegistered) {
        List<String> result = new ArrayList<>();
        for (CodexRules.Milestone m : rules.totalMilestones()) {
            if (totalPercent >= m.percent() && !m.title().isEmpty()) {
                result.add(m.title());
            }
        }
        regionFish.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.id().compareTo(b.id())))
                .forEach(
                        e -> {
                            if (!e.getValue().isEmpty()
                                    && seasonRegistered.containsAll(e.getValue())) {
                                result.add(regionTitle(rules.regionTitlePrefix(), e.getKey()));
                            }
                        });
        return result;
    }
}
