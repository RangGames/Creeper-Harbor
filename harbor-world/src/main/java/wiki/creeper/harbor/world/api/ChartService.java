package wiki.creeper.harbor.world.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 해도 (docs 20 8.4 '해도'). 지역 안에서 청크가 바뀌면 비트맵에 표시하고, 완성도 25 · 50 · 75 · 95%에 보상을 준다. Quest의
 * chart_percent 목표는 {@link wiki.creeper.harbor.world.api.event.ChartProgressEvent}로 갱신한다. 메인 스레드
 * 전용.
 */
public interface ChartService {

    /** 접속 중인 플레이어의 지역 해도 진행도. */
    Optional<ChartProgress> progress(UUID player, RegionId region);

    Map<RegionId, ChartProgress> progress(UUID player);

    /** 지역 해도 지도 아이템 (지역마다 MapView 하나를 같이 쓰고 플레이어별로 안개를 그린다). */
    Optional<ItemStack> chartItem(Player player, RegionId region);

    /**
     * @param visited 방문한 항해 가능 칸
     * @param total 항해 가능 칸 (마스크 크기)
     * @param claimedSteps 받은 보상 단계 수
     */
    record ChartProgress(RegionId region, int visited, int total, int claimedSteps) {

        /** 0~1. */
        public double ratio() {
            return total <= 0 ? 0 : Math.min(1.0, (double) visited / total);
        }
    }
}
