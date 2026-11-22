package wiki.creeper.harbor.quest.logic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Board candidate draw (docs 20 6.4 step 1): weighted draw without replacement seeded by hash(uuid,
 * cycle). The same input gives the same list regardless of server or restart.
 */
public final class BoardRoller {

    private BoardRoller() {}

    public record Candidate(QuestId quest, int weight) {}

    public static List<QuestId> pick(UUID player, String cycle, List<Candidate> pool, int count) {
        List<Candidate> left = new ArrayList<>(pool);
        // sort by ID so the result does not depend on input order
        left.sort(Comparator.comparing(c -> c.quest().id()));
        SplittableRandom random = new SplittableRandom(seed(player, cycle));
        List<QuestId> result = new ArrayList<>();
        while (result.size() < count && !left.isEmpty()) {
            long total = 0;
            for (Candidate c : left) {
                total += Math.max(1, c.weight());
            }
            long roll = random.nextLong(total);
            int chosen = left.size() - 1;
            for (int i = 0; i < left.size(); i++) {
                roll -= Math.max(1, left.get(i).weight());
                if (roll < 0) {
                    chosen = i;
                    break;
                }
            }
            result.add(left.remove(chosen).quest());
        }
        return List.copyOf(result);
    }

    /** FNV-1a 64-bit (fixed regardless of JVM). */
    static long seed(UUID player, String cycle) {
        long h = 0xcbf29ce484222325L;
        for (byte b : (player + "|" + cycle).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
