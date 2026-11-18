package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * World가 읽고 쓰는 플래그 이름 (Core FlagService). Quest는 퀘스트를 완료하면 {@link #questDone}을, 장 마지막 퀘스트를 끝내면
 * story/ch&lt;N&gt;_done을 세운다 (regions.yml unlock.quest · world/regions.yml unlock.flags). World는 첫
 * 방문 때 {@link #visited}를 세운다.
 */
public final class WorldFlags {

    private WorldFlags() {}

    /** 지역 첫 방문 플래그 {@code visited/<지역 이름>} (시즌 범위, World가 첫 방문 때 세운다. Economy 레시피·상점 해금이 읽는다). */
    public static String visited(RegionId region) {
        return "visited/" + region.id().name();
    }

    /**
     * 퀘스트 완료 플래그 = 퀘스트 ID 경로 그대로 (예: quest/main/ch1_dark_lighthouse). Quest가 완료 때 세운다 (메인은 영구, 나머지는
     * 시즌 범위).
     */
    public static String questDone(QuestId quest) {
        return quest.id().path();
    }
}
