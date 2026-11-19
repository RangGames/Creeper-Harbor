package wiki.creeper.harbor.common.proxy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /**
     * 백엔드 → 프록시: 로비에 있는 플레이어를 본 서버 대기열에 넣어 달라 (/출항과 같음, NPC·GUI·Skript용, 추가 제안). 로비에 있지 않으면 무시한다.
     */
    public record DepartRequest(UUID player, String origin) {}

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
     * @param formatted 백엔드가 만든 채팅 줄 (Adventure JSON, PlaceholderAPI까지 적용, 추가 제안). 메시지 자리는 {@link
     *     #MESSAGE_SLOT} 글자로 비워 두고 프록시가 걸러낸 메시지로 바꾼다. null이면 프록시의 chat.format을 쓴다
     */
    public record ChatMessage(
            UUID sender,
            String senderName,
            String server,
            String channel,
            String message,
            List<UUID> recipients,
            @Nullable String formatted) {}

    /**
     * 로비가 이 플레이어에게 적용한 리소스팩 묶음 서명 (Redis 키 접두사 + UUID, 추가 제안). 같은 접속 안에서 로비에 다시 오면 같은 팩을 또 보내지 않는다.
     * 프록시가 접속을 끊을 때 지운다 (남아도 {@link #PACK_APPLIED_TTL_SECONDS} 뒤 사라짐).
     */
    public static final String PACK_APPLIED_PREFIX = "harbor:pack_applied:";

    public static final long PACK_APPLIED_TTL_SECONDS = 12L * 3600;

    /** {@link ChatMessage#formatted}의 메시지 자리 표시 (사용자 문구에 나올 일 없는 사용자 정의 영역 문자). */
    public static final String MESSAGE_SLOT = "\uE000message\uE000";

    /**
     * Boss → 프록시: 파티를 인스턴스 서버로 보내 달라 ('17-4' 10.4). server는 Boss가 harbor:instance로 배정한 서버 ID다. 프록시는
     * 서버를 고르지 않고 이동만 시킨다.
     */
    public record InstanceRequest(
            String requestId, List<UUID> party, String server, String origin) {}

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
