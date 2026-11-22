package wiki.creeper.harbor.quest.service;

import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookContext;

/**
 * Season change (docs 20 6.4): non-main quest_progress rows and quest_board are archived and
 * deleted by the Season reset_plan (main rows are carried over with their original season value via
 * keep_where). Season-scoped flags (chronicle recap quest/recap/*) are reset together by the
 * player_flags cleanup. Quest only holds in-memory caches, which a server restart clears, so this
 * only writes a log entry (idempotent).
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
