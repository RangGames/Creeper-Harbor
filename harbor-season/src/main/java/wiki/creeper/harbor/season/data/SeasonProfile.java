package wiki.creeper.harbor.season.data;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * 플레이어의 Season 데이터 (PlayerComponent, docs 20 11.3 SeasonState). 변경 불가 값이고 바꿀 때는 새 값으로 교체한다.
 *
 * @param lastSeason 마지막으로 정리(SeasonGate)한 시즌. 0이면 정리한 적 없음
 * @param equipped 장착 칭호 (없으면 null)
 * @param titles 보유 칭호 캐시
 * @param stored season_player 행이 DB에 있는지 (없으면 첫 접속 때 바로 만든다)
 */
public record SeasonProfile(
        int lastSeason, @Nullable TitleId equipped, Set<TitleId> titles, boolean stored) {

    public SeasonProfile {
        titles = Set.copyOf(titles);
    }

    public SeasonProfile markStored() {
        return new SeasonProfile(lastSeason, equipped, titles, true);
    }

    public SeasonProfile withLastSeason(int season) {
        return new SeasonProfile(season, equipped, titles, stored);
    }

    public SeasonProfile withEquipped(@Nullable TitleId title) {
        return new SeasonProfile(lastSeason, title, titles, stored);
    }

    public SeasonProfile withTitle(TitleId title) {
        Set<TitleId> copy = new HashSet<>(titles);
        copy.add(title);
        return new SeasonProfile(lastSeason, equipped, copy, stored);
    }

    public SeasonProfile withoutTitle(TitleId title) {
        Set<TitleId> copy = new HashSet<>(titles);
        copy.remove(title);
        return new SeasonProfile(
                lastSeason, title.equals(equipped) ? null : equipped, copy, stored);
    }
}
