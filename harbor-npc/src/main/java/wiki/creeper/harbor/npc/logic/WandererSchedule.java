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
 * Wanderer schedule calculation (docs 20 5.4, no server API). Slot numbers count slotHours from
 * midnight in the time zone (0, 3, 6... o'clock Asia/Seoul). The candidate is a weighted draw
 * seeded by hash(npc, season, slot number), so it is the same before and after a restart without
 * saving anything.
 */
public final class WandererSchedule {

    private WandererSchedule() {}

    /** Slot number: in slotHours units since 1970-01-01 00:00 in the time zone. */
    public static long slot(Instant now, ZoneId zone, int slotHours) {
        long offset = zone.getRules().getOffset(now).getTotalSeconds();
        long local = now.getEpochSecond() + offset;
        return Math.floorDiv(local, slotHours * 3600L);
    }

    /** When the slot ends (the next slot starts). */
    public static Instant slotEnd(long slot, ZoneId zone, int slotHours) {
        long localEnd = (slot + 1) * slotHours * 3600L;
        // Re-derive the zone offset at the slot boundary (fixed for Asia/Seoul, which has no DST)
        Instant guess = Instant.ofEpochSecond(localEnd);
        long offset = zone.getRules().getOffset(guess).getTotalSeconds();
        return Instant.ofEpochSecond(localEnd - offset);
    }

    /** Local time the slot starts (for display). */
    public static ZonedDateTime slotStart(long slot, ZoneId zone, int slotHours) {
        return slotEnd(slot - 1, zone, slotHours).atZone(zone);
    }

    /** Deterministic seed. String.hashCode is fixed by the spec, so it is the same across JVMs. */
    public static long seed(String npcId, int season, long slot) {
        long h = npcId.hashCode();
        h = h * 0x9E3779B97F4A7C15L + season;
        h = h * 0x9E3779B97F4A7C15L + slot;
        return mix(h);
    }

    /** Weighted draw. Empty if there are no candidates. */
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

    /** Whether a conditional candidate (time, weather) should appear now. */
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
