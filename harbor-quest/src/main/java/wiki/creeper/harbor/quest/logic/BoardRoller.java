package wiki.creeper.harbor.quest.logic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * 게시판 후보 뽑기 (docs 20 6.4 1단계): 시드 hash(uuid, cycle)로 가중치 비복원 추첨. 같은 입력이면 서버·재시작과 관계없이 같은 목록이 나온다.
 */
public final class BoardRoller {

    private BoardRoller() {}

    public record Candidate(QuestId quest, int weight) {}

    public static List<QuestId> pick(UUID player, String cycle, List<Candidate> pool, int count) {
        List<Candidate> left = new ArrayList<>(pool);
        // 입력 순서와 무관하게 같은 결과가 나오도록 ID 순으로 정렬
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

    /** FNV-1a 64비트 (JVM과 무관하게 고정). */
    static long seed(UUID player, String cycle) {
        long h = 0xcbf29ce484222325L;
        for (byte b : (player + "|" + cycle).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
