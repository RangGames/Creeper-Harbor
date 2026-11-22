package wiki.creeper.harbor.season;

import java.util.List;
import java.util.Map;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.content.HallOfFameRules;
import wiki.creeper.harbor.season.content.RankingBoards;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.content.SeasonBalance;
import wiki.creeper.harbor.season.content.SeasonDefs;
import wiki.creeper.harbor.season.content.TitleDef;

/**
 * Pulls Season types out of the current content snapshot. May be used while the module is enabling
 * (right after type registration, before the first load), so it returns an empty value if the type
 * is not there yet. Snapshots are immutable and may be read from any thread.
 */
public final class SeasonContentView {

    private final HarborApi api;

    public SeasonContentView(HarborApi api) {
        this.api = api;
    }

    public ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    public SeasonDefs defs() {
        return defs(snapshot());
    }

    public static SeasonDefs defs(ContentSnapshot snap) {
        try {
            return snap.single(SeasonDefs.TYPE);
        } catch (IllegalStateException e) {
            return new SeasonDefs(List.of());
        }
    }

    public ResetPlan plan() {
        try {
            return snapshot().single(ResetPlan.TYPE);
        } catch (IllegalStateException e) {
            return ResetPlan.empty();
        }
    }

    public RankingBoards boards() {
        try {
            return snapshot().single(RankingBoards.TYPE);
        } catch (IllegalStateException e) {
            return new RankingBoards(List.of());
        }
    }

    public HallOfFameRules hallOfFame() {
        try {
            return snapshot().single(HallOfFameRules.TYPE);
        } catch (IllegalStateException e) {
            return new HallOfFameRules(List.of());
        }
    }

    public Map<HarborId, TitleDef> titles() {
        return snapshot().all(TitleDef.TYPE);
    }

    public SeasonBalance balance() {
        try {
            return new SeasonBalance(snapshot().single(Balance.TYPE));
        } catch (IllegalStateException e) {
            return new SeasonBalance(Balance.of(Map.of()));
        }
    }
}
