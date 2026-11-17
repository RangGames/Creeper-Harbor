package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** 접속 중인 플레이어 데이터. */
public interface PlayerService {

    Optional<HarborPlayer> get(UUID player);

    /** 접속 중인 플레이어의 데이터. 접속 전에 로드를 마치므로 접속 중이면 항상 있다. */
    HarborPlayer get(Player player);

    Collection<? extends HarborPlayer> online();

    /** 모듈 데이터 등록. 모듈 onEnable에서 부른다 (첫 접속 전에). */
    void register(PlayerComponent<?> component);
}
