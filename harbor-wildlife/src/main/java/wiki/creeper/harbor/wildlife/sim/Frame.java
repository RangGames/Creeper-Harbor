package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계산 한 주기의 출력 (이중 버퍼의 한 면). 만든 뒤에는 바뀌지 않으므로 메인 스레드가 그대로 읽는다. 위치 조회용으로 8블록 칸 색인을 함께 만든다.
 *
 * @param seq 주기 번호
 * @param computeNanos 이 주기 계산 시간
 */
public record Frame(
        long seq, List<FishOut> fish, List<SimEvent> events, long computeNanos, Index index) {

    public static final Frame EMPTY = new Frame(0, List.of(), List.of(), 0, Index.build(List.of()));

    public Frame {
        fish = List.copyOf(fish);
        events = List.copyOf(events);
    }

    /** 반경 안 개체 (가까운 순). */
    public List<FishOut> nearby(String world, double x, double y, double z, double radius) {
        List<FishOut> result = new ArrayList<>();
        double r2 = radius * radius;
        index.visit(
                world,
                x,
                z,
                radius,
                i -> {
                    FishOut f = fish.get(i);
                    if (f.distanceSq(x, y, z) <= r2) {
                        result.add(f);
                    }
                });
        result.sort(Comparator.comparingDouble(f -> f.distanceSq(x, y, z)));
        return result;
    }

    public @Nullable FishOut get(UUID id) {
        Integer i = index.byId().get(id);
        return i == null ? null : fish.get(i);
    }

    /** 수평 8블록 칸 색인. */
    public record Index(Map<String, Map<Long, int[]>> cells, Map<UUID, Integer> byId) {

        static final int CELL = 8;

        public static Index build(List<FishOut> fish) {
            Map<String, Map<Long, List<Integer>>> tmp = new HashMap<>();
            Map<UUID, Integer> ids = new HashMap<>();
            for (int i = 0; i < fish.size(); i++) {
                FishOut f = fish.get(i);
                ids.put(f.id(), i);
                long key =
                        MediumGrid.key(
                                Math.floorDiv((int) Math.floor(f.x()), CELL),
                                Math.floorDiv((int) Math.floor(f.z()), CELL));
                tmp.computeIfAbsent(f.world(), w -> new HashMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>())
                        .add(i);
            }
            Map<String, Map<Long, int[]>> cells = new HashMap<>();
            tmp.forEach(
                    (world, m) -> {
                        Map<Long, int[]> out = new HashMap<>();
                        m.forEach(
                                (k, list) ->
                                        out.put(
                                                k,
                                                list.stream()
                                                        .mapToInt(Integer::intValue)
                                                        .toArray()));
                        cells.put(world, out);
                    });
            return new Index(Map.copyOf(cells), Map.copyOf(ids));
        }

        void visit(
                String world,
                double x,
                double z,
                double radius,
                java.util.function.IntConsumer consumer) {
            Map<Long, int[]> m = cells.get(world);
            if (m == null) {
                return;
            }
            int x0 = Math.floorDiv((int) Math.floor(x - radius), CELL);
            int x1 = Math.floorDiv((int) Math.floor(x + radius), CELL);
            int z0 = Math.floorDiv((int) Math.floor(z - radius), CELL);
            int z1 = Math.floorDiv((int) Math.floor(z + radius), CELL);
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    int[] list = m.get(MediumGrid.key(cx, cz));
                    if (list != null) {
                        for (int i : list) {
                            consumer.accept(i);
                        }
                    }
                }
            }
        }
    }
}
