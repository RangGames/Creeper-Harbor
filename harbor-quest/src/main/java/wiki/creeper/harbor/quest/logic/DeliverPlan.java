package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wiki.creeper.harbor.quest.content.Objective;

/**
 * deliver 목표의 아이템 고르기 (docs 20 6.4): 미리 세지 않고 보고할 때 인벤토리를 검사한다. 조건에 맞는 아이템을 품질 낮은 순으로 고르고, 한 아이템은
 * 목표 하나에만 쓴다. 순수 계산 (인벤토리는 {@link Slot} 목록으로 받는다).
 */
public final class DeliverPlan {

    /**
     * 인벤토리 한 칸.
     *
     * @param precious ★5·고유 ID 아이템 (확인 Dialog를 거친다)
     */
    public record Slot(int index, CatchFacts facts, int amount, boolean precious) {}

    /**
     * 결과.
     *
     * @param take 칸 번호 → 가져갈 수
     * @param missing 목표 key → 모자란 수 (비어 있으면 충족)
     */
    public record Result(
            Map<Integer, Integer> take, Map<String, Integer> missing, boolean precious) {
        public boolean ok() {
            return missing.isEmpty();
        }
    }

    private DeliverPlan() {}

    public static Result plan(List<Objective> objectives, List<Slot> slots) {
        Map<Integer, Integer> left = new HashMap<>();
        slots.forEach(s -> left.put(s.index(), s.amount()));
        Map<Integer, Integer> take = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        boolean precious = false;
        List<Slot> sorted = new ArrayList<>(slots);
        // 품질 낮은 순 → 귀한 아이템은 뒤로 → 칸 순서
        sorted.sort(
                Comparator.comparingInt((Slot s) -> s.facts().quality())
                        .thenComparing(Slot::precious)
                        .thenComparingInt(Slot::index));
        for (Objective o : objectives) {
            if (o.tracked()) {
                continue;
            }
            int need = o.count();
            for (Slot s : sorted) {
                if (need <= 0) {
                    break;
                }
                int available = left.getOrDefault(s.index(), 0);
                if (available <= 0 || !Matchers.matchesFish(o, s.facts())) {
                    continue;
                }
                int used = Math.min(available, need);
                left.put(s.index(), available - used);
                take.merge(s.index(), used, Integer::sum);
                need -= used;
                precious |= s.precious();
            }
            if (need > 0) {
                missing.put(o.key(), need);
            }
        }
        return new Result(take, missing, precious);
    }
}
