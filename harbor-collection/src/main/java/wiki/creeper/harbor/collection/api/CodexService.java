package wiki.creeper.harbor.collection.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/** 도감 조회 (Quest 조건, Shop 기념 스킨 구매 조건). 접속 중인 플레이어만. 메인 스레드. */
public interface CodexService {

    Optional<CodexEntry> entry(UUID player, FishId fish, CodexScope scope);

    boolean isRegistered(UUID player, FishId fish, CodexScope scope);

    /** 달성률 0~100. region이 있으면 그 지역만 (신화 제외). */
    double percent(UUID player, CodexScope scope, @Nullable RegionId region);

    void open(Player viewer, UUID owner, int page);
}
