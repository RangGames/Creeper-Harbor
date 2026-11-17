package wiki.creeper.harbor.common.proxy;

import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.redis.Channel;

/**
 * 백엔드 ↔ 프록시 메시지 (docs 20 1.2, 추가 제안). 한 Redis 채널에 여러 메시지 형식이 오가며, 봉투의 type(레코드 이름)으로 구분한다.
 *
 * <ul>
 *   <li>{@code harbor:proxy}: 백엔드 → 프록시 (MoveRequest, TransferReady, PackStatus, ChatMessage,
 *       InstanceRequest)
 *   <li>{@code harbor:proxy:<서버 ID>}: 프록시 → 특정 백엔드 (TransferPrepare, TransferAbort, MoveResult,
 *       SaveAll)
 *   <li>{@code harbor:server}: 백엔드 → 프록시, 5초마다 (ServerHeartbeat)
 * </ul>
 */
public final class ProxyMessages {

    public static final String TO_PROXY = "harbor:proxy";
    public static final String HEARTBEAT = "harbor:server";

    private ProxyMessages() {}

    /** 프록시 → 특정 백엔드 채널 이름. */
    public static String toServer(String serverId) {
        return TO_PROXY + ":" + serverId;
    }

    public static <T extends Record> Channel<T> toProxy(Class<T> type) {
        return new Channel<>(TO_PROXY, type);
    }

    public static <T extends Record> Channel<T> toServer(String serverId, Class<T> type) {
        return new Channel<>(toServer(serverId), type);
    }

    // ------------------------------------------------------------------ 서버 이동

    /** 백엔드 → 프록시: 플레이어를 옮겨 달라 (/로비, 인스턴스 입장, 귀환 …). */
    public record MoveRequest(
            String requestId, UUID player, String origin, String destination, String reason) {}

    /** 프록시 → 출발 서버: 저장하고 잠금을 handoff로 바꿔라. */
    public record TransferPrepare(String requestId, UUID player, String destination) {}

    /** 출발 서버 → 프록시: 준비 결과. */
    public record TransferReady(String requestId, UUID player, boolean ok, String reason) {}

    /** 프록시 → 출발 서버: 이동 실패, 잠금을 owned로 되돌려라. */
    public record TransferAbort(String requestId, UUID player) {}

    /** 프록시 → MoveRequest를 보낸 서버: 최종 결과 (추가 제안). */
    public record MoveResult(String requestId, UUID player, boolean ok, String reason) {}

    /** 프록시 → 모든 백엔드: 점검으로 끊기 전에 저장하라. */
    public record SaveAll(String reason) {}

    // ------------------------------------------------------------------ 로비 · 대기열

    /** 로비 Core → 프록시: 리소스팩 적용 결과. LOADED만 대기열에 들어간다. */
    public record PackStatus(UUID player, Status status) {
        /** 적용 결과. */
        public enum Status {
            LOADED,
            DECLINED,
            FAILED
        }
    }

    /** 프록시 → 로비: 대기열 순번 표시. */
    public record QueueStatus(UUID player, int position, int etaSeconds) {}

    // ------------------------------------------------------------------ 채팅 · 인스턴스 · 상태

    /**
     * 백엔드 → 프록시: 채팅 한 줄. 서명 채팅을 프록시에서 취소하면 접속이 끊기므로 백엔드가 취소하고 넘긴다.
     *
     * @param channel global · server · whisper · party
     * @param recipients 귓속말·파티 수신자 (그 밖은 빈 목록)
     */
    public record ChatMessage(
            UUID sender,
            String senderName,
            String server,
            String channel,
            String message,
            List<UUID> recipients) {}

    /** Boss·World → 프록시: 파티를 인스턴스로 보내 달라. kind: dungeon · raid. */
    public record InstanceRequest(String requestId, List<UUID> party, String kind, String origin) {}

    /** 백엔드 → 프록시, 5초마다. */
    public record ServerHeartbeat(
            String serverId,
            String role,
            int online,
            int capacity,
            double tps,
            boolean accepting) {}

    public static final Channel<ServerHeartbeat> HEARTBEATS =
            new Channel<>(HEARTBEAT, ServerHeartbeat.class);
}
