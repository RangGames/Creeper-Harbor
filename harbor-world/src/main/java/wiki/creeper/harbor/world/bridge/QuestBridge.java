package wiki.creeper.harbor.world.bridge;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.world.api.RegionService;

/**
 * Quest 연결 (선택). Quest의 기본 구현(레벨 + 해금 퀘스트)보다 높은 우선순위로 "해금한 가장 높은 지역"을 등록한다 (World는 진입 조건 전체로 판정).
 * Harbor-Quest가 있을 때만 이 클래스를 불러온다.
 */
public final class QuestBridge {

    private QuestBridge() {}

    public static void register(Plugin plugin, RegionService regions) {
        RegionProgressProvider provider =
                uuid -> {
                    Player player = Bukkit.getPlayer(uuid);
                    return player == null
                            ? Optional.empty()
                            : Optional.of(regions.highestUnlocked(player).id());
                };
        Bukkit.getServicesManager()
                .register(RegionProgressProvider.class, provider, plugin, ServicePriority.High);
    }
}
