package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 도감 칭호 키 규칙 (docs 20 4.4). 칭호 키는 codex.yml의 이름 그대로(codex_25, codex_region/harbor)이고, Season
 * TitleId.of가 접두사를 붙인다 (harbor:title/codex_25). 서버 API를 쓰지 않아 단위 테스트와 harbor-tools가 같이 쓴다.
 */
public final class CodexTitles {

    /** Season TitleService 참조 ID 최대 길이. */
    public static final int REF_MAX = 64;

    private CodexTitles() {}

    /** 지역 완성 칭호 키: 접두사/지역 이름 (예 codex_region/harbor). */
    public static String regionTitle(String prefix, RegionId region) {
        return prefix + "/" + region.id().name();
    }

    /** 칭호 키 → 전체 칭호 ID (Season TitleId.of와 같은 규칙: 네임스페이스 기본 harbor, 경로에 title/ 접두사). */
    public static HarborId titleId(String key) {
        HarborId id = HarborId.parse(key);
        return id.hasPrefix("title/") ? id : new HarborId(id.namespace(), "title/" + id.path());
    }

    /** 지급 참조 ID {@code codex:<시즌>:<키>} (64자를 넘으면 뒤를 자른다. 칭호는 (uuid, 칭호) 유니크라 참조 ID는 기록용). */
    public static String refId(int season, String key) {
        String ref = "codex:" + season + ":" + key;
        return ref.length() <= REF_MAX ? ref : ref.substring(0, REF_MAX);
    }

    /**
     * 지금 도감 상태로 받아야 하는 칭호 키 (대기열·Season이 생기기 전 달성분 지급용). 누적 달성률 단계와, 시즌 도감으로 완성한 지역.
     *
     * @param totalPercent 누적 도감 달성률 (0~100)
     * @param regionFish 지역별 분모 (신화 제외)
     * @param seasonRegistered 시즌 도감 등록 어종
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
