package wiki.creeper.harbor.world.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 플레이어별 World 데이터 (docs 20 8.3 PlayerComponent&lt;WorldState&gt;). 변경 불가: 바꿀 때는 with…로 새 값을 만들어
 * HarborPlayer.set. 해도 비트맵은 DB에서 읽은 원본 바이트로 두고(마스크는 콘텐츠에 따라 바뀌므로), 쓰는 쪽(ChartManager)이 마스크와 합쳐 읽는다.
 *
 * @param visited 이번 시즌 방문 지역 (region_visit)
 * @param charts 지역별 해도 원본 (sea_chart)
 * @param cold 혹한 게이지 (0~100)
 * @param oxygen 산소 게이지 (초, −1 = 가득)
 * @param safe 마지막 안전 위치 (서버 이동·재접속에 이어 감)
 */
public record WorldState(
        Set<RegionId> visited,
        Map<RegionId, ChartRow> charts,
        double cold,
        double oxygen,
        @Nullable SafePos safe) {

    /** 해도 행 (원본 바이트는 복사해서 들고 있어 바뀌지 않는다). */
    public static final class ChartRow {

        private final byte[] bits;
        private final int visited;
        private final int claimed;

        public ChartRow(byte[] bits, int visited, int claimed) {
            this.bits = bits.clone();
            this.visited = visited;
            this.claimed = claimed;
        }

        public byte[] bits() {
            return bits.clone();
        }

        public int visited() {
            return visited;
        }

        public int claimed() {
            return claimed;
        }
    }

    /** 안전 위치 (콘텐츠 월드 이름 기준). */
    public record SafePos(String world, double x, double y, double z) {}

    public static final WorldState EMPTY = new WorldState(Set.of(), Map.of(), 0, -1, null);

    public WorldState {
        visited = Set.copyOf(visited);
        charts = Map.copyOf(charts);
    }

    public WorldState withVisited(RegionId region) {
        Set<RegionId> next = new HashSet<>(visited);
        next.add(region);
        return new WorldState(next, charts, cold, oxygen, safe);
    }

    public WorldState withChart(RegionId region, ChartRow row) {
        Map<RegionId, ChartRow> next = new HashMap<>(charts);
        next.put(region, row);
        return new WorldState(visited, next, cold, oxygen, safe);
    }

    public WorldState withGauges(double newCold, double newOxygen) {
        return new WorldState(visited, charts, newCold, newOxygen, safe);
    }

    public WorldState withSafe(@Nullable SafePos pos) {
        return new WorldState(visited, charts, cold, oxygen, pos);
    }
}
