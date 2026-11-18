package wiki.creeper.harbor.npc.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.ReputationLogEntry;
import wiki.creeper.harbor.npc.api.ReputationSource;

/**
 * PlayerComponent<NpcData> (docs 20 5.3). 변경 불가 값이고 바꿀 때는 새 값을 넣는다. 저장은 호감도만 (평판 기록·최고 등급은 바뀔 때 즉시
 * 쓴다). 대화 세션·컷씬 상태는 저장하지 않으므로 각 관리자의 메모리에 둔다.
 *
 * @param repDay repToday의 게임 날짜
 * @param repToday 출처별 오늘 받은 평판 (하루 상한)
 * @param recent 최근 평판 기록 (최신순)
 * @param refs 이번 시즌에 이미 받은 참조 ID (메모리 최근 목록, DB 유니크가 최종 방어)
 * @param bestTier 시즌 간 최고 도달 평판 등급
 */
public record NpcData(
        Map<NpcId, AffinityState> affinity,
        LocalDate repDay,
        Map<ReputationSource, Integer> repToday,
        List<ReputationLogEntry> recent,
        Set<String> refs,
        int bestTier) {

    /** 메모리에 둘 참조 ID 수. */
    public static final int MAX_REFS = 256;

    public NpcData {
        affinity = Map.copyOf(affinity);
        repToday = repToday.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(repToday));
        recent = List.copyOf(recent);
        refs = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(refs));
    }

    public static NpcData empty(LocalDate day) {
        return new NpcData(Map.of(), day, Map.of(), List.of(), Set.of(), 1);
    }

    public AffinityState affinity(NpcId npc) {
        return affinity.getOrDefault(npc, AffinityState.EMPTY);
    }

    public NpcData withAffinity(NpcId npc, AffinityState state) {
        Map<NpcId, AffinityState> next = new HashMap<>(affinity);
        next.put(npc, state);
        return new NpcData(next, repDay, repToday, recent, refs, bestTier);
    }

    /** 오늘 이 출처로 받은 평판 (날짜가 바뀌었으면 0). */
    public int repToday(ReputationSource source, LocalDate today) {
        return today.equals(repDay) ? repToday.getOrDefault(source, 0) : 0;
    }

    public NpcData withReputation(LocalDate today, ReputationLogEntry entry, int maxRecent) {
        Map<ReputationSource, Integer> todayMap = new EnumMap<>(ReputationSource.class);
        if (today.equals(repDay)) {
            todayMap.putAll(repToday);
        }
        if (entry.delta() > 0) {
            todayMap.merge(entry.source(), entry.delta(), Integer::sum);
        }
        List<ReputationLogEntry> nextRecent = new ArrayList<>();
        nextRecent.add(entry);
        nextRecent.addAll(recent);
        while (nextRecent.size() > maxRecent) {
            nextRecent.removeLast();
        }
        Set<String> nextRefs = new LinkedHashSet<>(refs);
        String ref = entry.refId();
        if (ref != null) {
            nextRefs.add(ref);
            while (nextRefs.size() > MAX_REFS) {
                nextRefs.remove(nextRefs.iterator().next());
            }
        }
        return new NpcData(affinity, today, todayMap, nextRecent, nextRefs, bestTier);
    }

    public NpcData withBestTier(int tier) {
        return new NpcData(affinity, repDay, repToday, recent, refs, Math.max(bestTier, tier));
    }
}
