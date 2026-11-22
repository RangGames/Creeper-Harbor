package wiki.creeper.harbor.collection.data;

import java.util.HashMap;
import java.util.Map;
import wiki.creeper.harbor.collection.api.CollectionEntry;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Player collection (PlayerComponent, immutable). At most 145 × 2 entries per player, so memory
 * cost is negligible.
 *
 * @param donations donations per species this season (for the personal point cap)
 */
public record CollectionData(
        Map<FishId, CollectionEntry> total,
        Map<FishId, CollectionEntry> season,
        Map<FishId, Integer> hints,
        Map<FishId, Integer> donations) {

    public static final CollectionData EMPTY =
            new CollectionData(Map.of(), Map.of(), Map.of(), Map.of());

    public CollectionData {
        total = Map.copyOf(total);
        season = Map.copyOf(season);
        hints = Map.copyOf(hints);
        donations = Map.copyOf(donations);
    }

    public CollectionData withCatch(
            FishId fish, CollectionEntry totalEntry, CollectionEntry seasonEntry) {
        Map<FishId, CollectionEntry> t = new HashMap<>(total);
        Map<FishId, CollectionEntry> s = new HashMap<>(season);
        t.put(fish, totalEntry);
        s.put(fish, seasonEntry);
        return new CollectionData(t, s, hints, donations);
    }

    public CollectionData without(FishId fish) {
        Map<FishId, CollectionEntry> t = new HashMap<>(total);
        Map<FishId, CollectionEntry> s = new HashMap<>(season);
        t.remove(fish);
        s.remove(fish);
        return new CollectionData(t, s, hints, donations);
    }

    public CollectionData withHint(FishId fish, int level) {
        Map<FishId, Integer> h = new HashMap<>(hints);
        h.put(fish, level);
        return new CollectionData(total, season, h, donations);
    }

    public CollectionData withDonation(FishId fish) {
        Map<FishId, Integer> d = new HashMap<>(donations);
        d.merge(fish, 1, Integer::sum);
        return new CollectionData(total, season, hints, d);
    }
}
