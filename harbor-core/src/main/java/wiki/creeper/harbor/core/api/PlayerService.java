package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** 접속 중인 플레이어 데이터. */
public interface PlayerService {

    Optional<HarborPlayer> get(UUID player);

    /**
     * 이름으로 플레이어를 찾는다 (대소문자 무시, 추가 제안). 이 서버 접속자를 먼저 보고, 없으면 players 테이블(다른 서버 접속자·오프라인, 마지막 접속이 가장
     * 최근인 계정)에서 찾는다. 메인 스레드에서 완료된다.
     */
    CompletableFuture<Optional<wiki.creeper.harbor.common.model.PlayerRef>> find(String name);

    /** 접속 중인 플레이어의 데이터. 접속 전에 로드를 마치므로 접속 중이면 항상 있다. */
    HarborPlayer get(Player player);

    Collection<? extends HarborPlayer> online();

    /** 모듈 데이터 등록. 모듈 onEnable에서 부른다 (첫 접속 전에). */
    void register(PlayerComponent<?> component);

    /**
     * 이 플레이어의 바뀐 데이터(기본 정보 + 모듈 데이터)를 지금 DB에 쓴다. 경매 등록·보스 보상처럼 바로 저장해야 하는 곳에서 쓴다 (지연 쓰기 큐에서 이 플레이어
     * 것만 비운다). 메인 스레드에서 부르고, future는 커밋 후 완료된다. 접속 중이 아니면 바로 완료.
     */
    CompletableFuture<Void> saveNow(UUID player);
}
