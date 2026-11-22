package wiki.creeper.harbor.collection.data;

import java.util.HashMap;
import java.util.Map;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Player codex (PlayerComponent, immutable). At most 145 × 2 entries per player, so memory cost is
 * negligible.
 *
 * @param donations donations per species this season (for the personal point cap)
 */
public record CodexData(
        Map<FishId, CodexEntry> total,
        Map<FishId, CodexEntry> season,
        Map<FishId, Integer> hints,
        Map<FishId, Integer> donations) {

    public static final CodexData EMPTY = new CodexData(Map.of(), Map.of(), Map.of(), Map.of());

    public CodexData {
        total = Map.copyOf(total);
        season = Map.copyOf(season);
        hints = Map.copyOf(hints);
        donations = Map.copyOf(donations);
    }

    public CodexData withCatch(FishId fish, CodexEntry totalEntry, CodexEntry seasonEntry) {
        Map<FishId, CodexEntry> t = new HashMap<>(total);
        Map<FishId, CodexEntry> s = new HashMap<>(season);
        t.put(fish, totalEntry);
        s.put(fish, seasonEntry);
        return new CodexData(t, s, hints, donations);
    }

    public CodexData without(FishId fish) {
        Map<FishId, CodexEntry> t = new HashMap<>(total);
        Map<FishId, CodexEntry> s = new HashMap<>(season);
        t.remove(fish);
        s.remove(fish);
        return new CodexData(t, s, hints, donations);
    }

    public CodexData withHint(FishId fish, int level) {
        Map<FishId, Integer> h = new HashMap<>(hints);
        h.put(fish, level);
        return new CodexData(total, season, h, donations);
    }

    public CodexData withDonation(FishId fish) {
        Map<FishId, Integer> d = new HashMap<>(donations);
        d.merge(fish, 1, Integer::sum);
        return new CodexData(total, season, hints, d);
    }
}
