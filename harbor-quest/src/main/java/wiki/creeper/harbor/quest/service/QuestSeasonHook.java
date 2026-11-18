package wiki.creeper.harbor.quest.service;

import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookContext;

/**
 * 시즌 전환 (docs 20 6.4): 메인이 아닌 quest_progress 행과 quest_board는 Season reset_plan이 아카이브 후 지운다 (메인 행은
 * keep_where로 원래 season 값 그대로 이월). 시즌 범위 플래그(연대기 회상 quest/recap/*)는 player_flags 정리로 함께 초기화된다.
 * Quest는 메모리 캐시만 들고 있어 서버 재시작으로 비워지므로 여기서는 기록만 남긴다 (여러 번 불려도 같다).
 */
final class QuestSeasonHook implements SeasonHook {

    @Override
    public String id() {
        return "quest.progress";
    }

    @Override
    public void onReset(SeasonHookContext ctx) {
        ctx.report(
                "quest: 메인 진행 이월 (quest_id LIKE 'harbor:quest/main/%'), 의뢰 게시판·시즌 플래그는 reset_plan이 정리");
    }
}
