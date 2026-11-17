package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;

/**
 * 프록시에 서버 이동을 요청한다 (docs 20 1.2). 이동은 반드시 프록시의 MoveCoordinator를 거친다 (저장 없이 옮기는 길을 막기 위해).
 * Boss·World는 이것만 쓴다. 결과 future는 메인 스레드에서 완료된다.
 */
public interface ProxyBridge {

    /**
     * @param reason 기록용 사유 (lobby, instance, return …)
     */
    CompletableFuture<MoveResult> requestMove(UUID player, String destination, String reason);

    /** 파티를 인스턴스 서버로 보낸다. kind: dungeon · raid. 부분 실패면 ok=false와 사유. */
    CompletableFuture<MoveResult> requestInstance(List<UUID> party, String kind);
}
