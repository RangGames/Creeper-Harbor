package wiki.creeper.harbor.collection.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.common.id.FishId;

/**
 * 수족관 진행 화면 계산 (추가 제안, 서버 API 없음). 단계별 기부율 문턱과 달성 여부, 다음 단계까지 남은 어종 수, 기부한 어종·남은 어종 필터, 인벤토리에서 바로 낼
 * 수 있는 어종 표시.
 */
public final class AquariumView {

    /** 어종 필터. */
    public enum Filter {
        MISSING,
        DONATED,
        ALL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Filter next() {
            return switch (this) {
                case MISSING -> DONATED;
                case DONATED -> ALL;
                case ALL -> MISSING;
            };
        }
    }

    /** 단계 한 줄. needed는 이 단계에 필요한 어종 수 (기부 가능 어종 × 문턱, 올림). */
    public record StageRow(AquariumRules.Stage stage, int needed, boolean reached, boolean next) {}

    /**
     * 어종 한 칸.
     *
     * @param inventorySlot 플레이어 인벤토리에서 이 어종이 든 첫 칸 (-1이면 없음)
     */
    public record SpeciesRow(FishId fish, int no, boolean donated, int inventorySlot) {

        public boolean inInventory() {
            return inventorySlot >= 0;
        }
    }

    private AquariumView() {}

    /** 단계 표 (단계 번호 순). 현재 단계 이하가 달성, 그 다음 하나가 next. */
    public static List<StageRow> stages(AquariumRules rules, int currentStage, int donatable) {
        List<StageRow> out = new ArrayList<>();
        boolean nextMarked = false;
        List<AquariumRules.Stage> sorted = new ArrayList<>(rules.stages());
        sorted.sort(Comparator.comparingInt(AquariumRules.Stage::stage));
        for (AquariumRules.Stage s : sorted) {
            boolean reached = s.stage() <= currentStage;
            boolean next = !reached && !nextMarked;
            if (next) {
                nextMarked = true;
            }
            out.add(new StageRow(s, needed(s.rate(), donatable), reached, next));
        }
        return List.copyOf(out);
    }

    /** 문턱을 넘는 데 필요한 어종 수 (올림). */
    public static int needed(double rate, int donatable) {
        return (int) Math.ceil(rate * donatable - 1e-9);
    }

    /** 다음 단계까지 더 필요한 어종 수 (다음 단계가 없으면 비어 있음). */
    public static Optional<Integer> remaining(List<StageRow> stages, int species) {
        return stages.stream()
                .filter(StageRow::next)
                .findFirst()
                .map(r -> Math.max(0, r.needed() - species));
    }

    /**
     * 어종 목록 (번호 순). 인벤토리에 있는 어종이 앞에 온다 (MISSING·ALL).
     *
     * @param donatable 기부 가능 어종과 번호
     * @param inventory 인벤토리에 든 어종 → 첫 칸
     */
    public static List<SpeciesRow> species(
            Map<FishId, Integer> donatable,
            Set<FishId> donated,
            Map<FishId, Integer> inventory,
            Filter filter) {
        List<SpeciesRow> out = new ArrayList<>();
        donatable.forEach(
                (fish, no) -> {
                    boolean d = donated.contains(fish);
                    boolean keep =
                            switch (filter) {
                                case ALL -> true;
                                case DONATED -> d;
                                case MISSING -> !d;
                            };
                    if (keep) {
                        out.add(new SpeciesRow(fish, no, d, inventory.getOrDefault(fish, -1)));
                    }
                });
        out.sort(
                Comparator.comparing((SpeciesRow r) -> !r.inInventory())
                        .thenComparingInt(SpeciesRow::no));
        return out;
    }
}
