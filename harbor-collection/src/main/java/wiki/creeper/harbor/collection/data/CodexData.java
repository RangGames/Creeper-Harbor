package wiki.creeper.harbor.collection.data;

import java.util.HashMap;
import java.util.Map;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.common.id.FishId;

/**
 * 플레이어 도감 (PlayerComponent, 변경 불가). 1인 최대 145 × 2 엔트리라 메모리 부담이 없다.
 *
 * @param donations 이번 시즌 어종별 기부 수 (개인 점수 상한 판정)
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
