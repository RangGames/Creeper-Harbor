package wiki.creeper.harbor.season.content;

import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.season.api.League;

/**
 * 랭킹 보드 정의 (season/rankings.yml, docs 20 11.3).
 *
 * @param id 보드 ID (소문자·숫자·밑줄). Redis 캐시 이름과 명령어에 쓴다
 * @param name 표시 이름 (MiniMessage)
 * @param source 원천: records · level · codex_season · boss_records, 또는 다른 모듈이 등록한 원천
 * @param subject 대상 (records = 어종 ID, boss_records = 보스 ID). 필요 없는 원천이면 null
 * @param leagues 집계할 리그 (첫 번째가 기본)
 * @param size 상위 몇 명까지 (기본 100)
 * @param refreshMinutes 집계 주기 (분, 기본 10)
 * @param icon GUI 아이콘 아이템 모델 (없으면 기본)
 */
public record RankingBoardDef(
        String id,
        String name,
        String source,
        @Nullable HarborId subject,
        List<League> leagues,
        int size,
        int refreshMinutes,
        @Nullable String icon) {

    public RankingBoardDef {
        leagues = List.copyOf(leagues);
    }

    public League defaultLeague() {
        return leagues.isEmpty() ? League.ALL : leagues.getFirst();
    }
}
