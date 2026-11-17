package wiki.creeper.harbor.common.session;

import java.util.UUID;
import wiki.creeper.harbor.common.redis.Channel;

/** Proxy ↔ Purpur 서버 이동 메시지. */
public final class SessionProtocol {

    /** Proxy → 출발 서버: 저장하고 넘겨 달라. */
    public record HandoffRequest(String requestId, UUID player, String from, String to) {}

    /**
     * 출발 서버 → Proxy: 결과.
     *
     * @param ok true면 저장을 마치고 잠금을 handoff 상태로 바꿈
     */
    public record HandoffReply(String requestId, UUID player, boolean ok, String reason) {}

    public static final Channel<HandoffRequest> HANDOFF_REQUEST =
            new Channel<>("harbor:session:handoff", HandoffRequest.class);
    public static final Channel<HandoffReply> HANDOFF_REPLY =
            new Channel<>("harbor:session:reply", HandoffReply.class);

    private SessionProtocol() {}
}
