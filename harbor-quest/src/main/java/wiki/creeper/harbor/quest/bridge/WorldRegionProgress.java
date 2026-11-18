package wiki.creeper.harbor.quest.bridge;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.world.api.RegionService;

/**
 * World 연결 (선택). Quest 기본 구현(레벨 + 해금 퀘스트)보다 높은 우선순위로 World 판정(진입 조건 전체)의 "해금한 가장 높은 지역"을 등록한다.
 * Harbor-World가 켜져 있을 때만 이 클래스를 불러온다.
 */
public final class WorldRegionProgress {

    private WorldRegionProgress() {}

    public static boolean register(Plugin plugin) {
        RegionService regions = Bukkit.getServicesManager().load(RegionService.class);
        if (regions == null) {
            return false;
        }
        RegionProgressProvider provider =
                uuid -> {
                    Player player = Bukkit.getPlayer(uuid);
                    return player == null
                            ? Optional.empty()
                            : Optional.of(regions.highestUnlocked(player).id());
                };
        Bukkit.getServicesManager()
                .register(RegionProgressProvider.class, provider, plugin, ServicePriority.High);
        return true;
    }
}
