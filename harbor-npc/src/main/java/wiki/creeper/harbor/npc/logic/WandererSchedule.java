package wiki.creeper.harbor.npc.logic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import wiki.creeper.harbor.npc.content.Conditions;
import wiki.creeper.harbor.npc.content.NpcDef;

/**
 * 떠돌이 일정 계산 (docs 20 5.4, 서버 API 없음). 칸 번호는 시간대 기준 자정부터 slotHours씩 센다 (Asia/Seoul 0·3·6…시). 후보는
 * hash(npc, 시즌, 칸 번호) 시드의 가중치 추첨이라 저장 없이도 재시작 전후가 같다.
 */
public final class WandererSchedule {

    private WandererSchedule() {}

    /** 칸 번호: 시간대 기준 1970-01-01 00:00부터 slotHours 단위. */
    public static long slot(Instant now, ZoneId zone, int slotHours) {
        long offset = zone.getRules().getOffset(now).getTotalSeconds();
        long local = now.getEpochSecond() + offset;
        return Math.floorDiv(local, slotHours * 3600L);
    }

    /** 칸이 끝나는 (다음 칸이 시작되는) 시각. */
    public static Instant slotEnd(long slot, ZoneId zone, int slotHours) {
        long localEnd = (slot + 1) * slotHours * 3600L;
        // 시간대 오프셋은 칸 경계 시각 기준으로 다시 맞춘다 (서머타임이 없는 Asia/Seoul은 고정)
        Instant guess = Instant.ofEpochSecond(localEnd);
        long offset = zone.getRules().getOffset(guess).getTotalSeconds();
        return Instant.ofEpochSecond(localEnd - offset);
    }

    /** 칸이 시작하는 현지 시각 (표시용). */
    public static ZonedDateTime slotStart(long slot, ZoneId zone, int slotHours) {
        return slotEnd(slot - 1, zone, slotHours).atZone(zone);
    }

    /** 결정적 시드. String.hashCode는 명세로 정해져 있어 JVM이 바뀌어도 같다. */
    public static long seed(String npcId, int season, long slot) {
        long h = npcId.hashCode();
        h = h * 0x9E3779B97F4A7C15L + season;
        h = h * 0x9E3779B97F4A7C15L + slot;
        return mix(h);
    }

    /** 가중치 추첨. 후보가 없으면 빈 값. */
    public static Optional<NpcDef.Candidate> pick(
            String npcId, int season, long slot, List<NpcDef.Candidate> candidates) {
        long total = 0;
        for (NpcDef.Candidate c : candidates) {
            total += Math.max(0, c.weight());
        }
        if (total <= 0) {
            return Optional.empty();
        }
        long roll = new SplittableRandom(seed(npcId, season, slot)).nextLong(total);
        for (NpcDef.Candidate c : candidates) {
            roll -= Math.max(0, c.weight());
            if (roll < 0) {
                return Optional.of(c);
            }
        }
        return Optional.of(candidates.getLast());
    }

    /** 조건부 후보(시간·날씨)가 지금 나타나야 하는지. */
    public static boolean visible(NpcDef.Candidate candidate, String time, String weather) {
        return Conditions.matches(candidate.time(), time)
                && Conditions.matches(candidate.weather(), weather);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
