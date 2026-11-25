package wiki.creeper.harbor.common.proxy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;

/**
 * Backend ↔ proxy messages (docs 20 1.2, proposed). Several message types share one Redis channel,
 * told apart by the envelope type (record name).
 *
 * <ul>
 *   <li>{@code harbor:proxy}: backend → proxy (MoveRequest, TransferReady, PackStatus, ChatMessage,
 *       InstanceRequest)
 *   <li>{@code harbor:proxy:<server ID>}: proxy → a specific backend (TransferPrepare,
 *       TransferAbort, MoveResult, SaveAll)
 *   <li>{@code harbor:server}: backend → proxy, every 5 seconds (ServerHeartbeat)
 *   <li>{@code harbor:network}: proxy → every backend (NetworkJoin, NetworkQuit)
 * </ul>
 */
public final class ProxyMessages {

    public static final String TO_PROXY = "harbor:proxy";
    public static final String HEARTBEAT = "harbor:server";
    public static final String NETWORK = "harbor:network";

    private ProxyMessages() {}

    /** Name of the proxy → specific backend channel. */
    public static String toServer(String serverId) {
        return TO_PROXY + ":" + serverId;
    }

    public static <T extends Record> Channel<T> toProxy(Class<T> type) {
        return new Channel<>(TO_PROXY, type);
    }

    public static <T extends Record> Channel<T> toServer(String serverId, Class<T> type) {
        return new Channel<>(toServer(serverId), type);
    }

    // ------------------------------------------------------------------ Server moves

    /** Backend → proxy: please move the player (/로비, instance entry, return …). */
    public record MoveRequest(
            String requestId, UUID player, String origin, String destination, String reason) {}

    /**
     * Backend → proxy: please put a player in the lobby into the main server queue (same as /출항;
     * for NPCs, GUIs and Skript, proposed). Ignored if not in the lobby.
     */
    public record DepartRequest(UUID player, String origin) {}

    /** Proxy → source server: save and switch the lock to handoff. */
    public record TransferPrepare(String requestId, UUID player, String destination) {}

    /** Source server → proxy: preparation result. */
    public record TransferReady(String requestId, UUID player, boolean ok, String reason) {}

    /** Proxy → source server: move failed, revert the lock to owned. */
    public record TransferAbort(String requestId, UUID player) {}

    /** Proxy → the server that sent the MoveRequest: final result (proposed). */
    public record MoveResult(String requestId, UUID player, boolean ok, String reason) {}

    /** Proxy → all backends: save before disconnecting for maintenance. */
    public record SaveAll(String reason) {}

    // ------------------------------------------------------------------ Lobby · queue

    /** Lobby Core → proxy: resource pack apply result. Only LOADED enters the queue. */
    public record PackStatus(UUID player, Status status) {
        /** Apply result. */
        public enum Status {
            LOADED,
            DECLINED,
            FAILED
        }
    }

    /** Proxy → lobby: queue position display. */
    public record QueueStatus(UUID player, int position, int etaSeconds) {}

    // ------------------------------------------------------------------ Chat · instances · status

    /**
     * Backend → proxy: one chat line. Cancelling signed chat on the proxy disconnects the player,
     * so the backend cancels it and forwards it.
     *
     * @param channel global · server · whisper · party
     * @param recipients whisper/party recipients (empty list otherwise)
     * @param formatted chat line built by the backend (Adventure JSON with PlaceholderAPI applied,
     *     proposed). The message slot is left as the {@link #MESSAGE_SLOT} character and the proxy
     *     replaces it with the filtered message. null means the proxy's chat.format is used
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
     * Signature of the resource pack bundle the lobby applied to this player (Redis key prefix +
     * UUID, proposed). Coming back to the lobby within the same connection does not resend the same
     * pack. Deleted when the proxy disconnects the player (if left over, it expires after {@link
     * #PACK_APPLIED_TTL_SECONDS}).
     */
    public static final String PACK_APPLIED_PREFIX = "harbor:pack_applied:";

    public static final long PACK_APPLIED_TTL_SECONDS = 12L * 3600;

    /**
     * Message slot marker in {@link ChatMessage#formatted} (a private-use character that never
     * appears in user text).
     */
    public static final String MESSAGE_SLOT = "\uE000message\uE000";

    /**
     * Boss → proxy: please send a party to an instance server ('17-4' 10.4). server is the server
     * ID Boss assigned via harbor:instance. The proxy does not pick a server; it only moves them.
     */
    public record InstanceRequest(
            String requestId, List<UUID> party, String server, String origin) {}

    /** Backend → proxy, every 5 seconds. */
    public record ServerHeartbeat(
            String serverId,
            String role,
            int online,
            int capacity,
            double tps,
            boolean accepting) {}

    public static final Channel<ServerHeartbeat> HEARTBEATS =
            new Channel<>(HEARTBEAT, ServerHeartbeat.class);

    // ------------------------------------------------------------------ Network presence

    /**
     * Proxy → every backend: the player connected to the network (proxy login done, before the
     * first backend). Not sent for server moves inside the network (proposed).
     */
    public record NetworkJoin(UUID player, String name) {}

    /**
     * Proxy → every backend: the player left the network. Sent only for players whose NetworkJoin
     * was sent. lastServer is the backend they were on ("" if they never reached one).
     */
    public record NetworkQuit(UUID player, String name, String lastServer) {}

    public static final Channel<NetworkJoin> NETWORK_JOINS =
            new Channel<>(NETWORK, NetworkJoin.class);

    public static final Channel<NetworkQuit> NETWORK_QUITS =
            new Channel<>(NETWORK, NetworkQuit.class);
}
