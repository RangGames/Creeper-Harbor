package wiki.creeper.harbor.season.data;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * A player's Season data (PlayerComponent, docs 20 11.3 SeasonState). An immutable value; changes
 * replace it with a new value.
 *
 * @param lastSeason last season cleaned up (SeasonGate). 0 = never cleaned
 * @param equipped equipped title (null if none)
 * @param titles owned title cache
 * @param stored whether the season_player row exists in the DB (if not, it is created right away on
 *     first login)
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
