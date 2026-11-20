package wiki.creeper.harbor.core.api;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 외부 연동 이벤트 발행 (추가 제안). Redis {@code harbor:events}로 모든 서버의 이벤트가 모이고, Harbor-Web이 웹훅으로 보내거나 REST로
 * 보여준다. 외부 서비스가 직접 구독해도 된다. 형식은 Common {@link wiki.creeper.harbor.common.event.ExternalEvent}.
 *
 * <p>게임 모듈 이벤트는 Harbor-Web이 자동으로 옮기므로 모듈이 직접 부를 일은 드물다. 다른 플러그인(Skript 등)이 자기 사건을 내보낼 때 쓴다.
 *
 * <pre>{@code
 * Harbor.api().events().emit("skript.lottery_won", player.getUniqueId(), player.getName(),
 *         Map.of("prize", 1000));
 * }</pre>
 *
 * 어느 스레드에서 불러도 된다 (발행은 비동기, 실패하면 로그만 남긴다).
 */
public interface ExternalEvents {

    /** type 형식: 소문자·숫자·밑줄을 점으로 잇는다 (모듈.사건). */
    String TYPE_PATTERN = "[a-z0-9_]+(\\.[a-z0-9_]+)+";

    /**
     * @param data 값은 글·수·참거짓·UUID·목록·지도 (그 밖은 toString)
     */
    void emit(String type, @Nullable UUID player, @Nullable String playerName, Map<String, ?> data);

    /** Redis가 없을 때 등: 아무것도 하지 않는다. */
    ExternalEvents NOOP = (type, player, playerName, data) -> {};
}
