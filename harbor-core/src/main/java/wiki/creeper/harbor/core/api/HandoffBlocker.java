package wiki.creeper.harbor.core.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * 서버 이동 전 훅 (docs 20 0.2). 출발 서버가 프록시의 이동 준비 요청을 받으면 저장 전에 메인 스레드에서 등록된 훅을 모두 부른다. 하나라도 거부하면 이동을
 * 취소하고, 지연이 있으면 모두 끝날 때까지 기다린 뒤 저장을 진행한다. {@link #MAX_DELAY_MILLIS} 안에 끝나지 않으면 이동을 취소한다 (저장본을 뜬 뒤
 * 거래가 아이템을 지급·회수하면 복제·유실되므로). Collection 대기 작업 훅, NPC 이동 거부 훅(컷씬 중), Economy 거래·기부 진행 중 지연을 합친 것이다.
 */
@FunctionalInterface
public interface HandoffBlocker {

    /** 최대 지연. 프록시 이동 준비 제한(5초) 안에 저장까지 끝나도록 4초로 둔다 (제안값). */
    long MAX_DELAY_MILLIS = 4_000;

    sealed interface Verdict permits Allow, Deny, Delay {}

    record Allow() implements Verdict {}

    /**
     * @param reason 프록시에 돌려줄 사유 코드 (예: cutscene). 메시지 키는 {@code core.handoff.blocked.<reason>}
     */
    record Deny(String reason) implements Verdict {}

    /** 진행 중인 작업이 끝나면 완료되는 future. 실패해도 이동은 진행한다. */
    record Delay(CompletableFuture<?> until) implements Verdict {}

    Verdict ALLOW = new Allow();

    Verdict check(Player player, String destination);
}
