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
 * PlayerComponent<NpcData> (docs 20 5.3). An immutable value; put a new value to change it. Only
 * affinity is saved this way (reputation log and best tier are written immediately on change).
 * Dialog session and cutscene state are not saved, so each manager keeps them in memory.
 *
 * @param repDay game date of repToday
 * @param repToday reputation received today per source (daily cap)
 * @param recent recent reputation log (newest first)
 * @param refs reference IDs already received this season (recent list in memory; the DB unique key
 *     is the final guard)
 * @param bestTier best reputation tier reached across seasons
 */
public record NpcData(
        Map<NpcId, AffinityState> affinity,
        LocalDate repDay,
        Map<ReputationSource, Integer> repToday,
        List<ReputationLogEntry> recent,
        Set<String> refs,
        int bestTier) {

    /** Number of reference IDs kept in memory. */
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

    /** Reputation received from this source today (0 if the date changed). */
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
