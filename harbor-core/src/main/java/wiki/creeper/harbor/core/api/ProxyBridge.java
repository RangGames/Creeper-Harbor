package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;

/**
 * Asks the proxy to transfer a player (docs 20 1.2). Transfers always go through the proxy's
 * MoveCoordinator (to block any path that moves without saving). Boss and World use only this.
 * Result futures complete on the main thread.
 */
public interface ProxyBridge {

    /**
     * @param reason reason for logging (lobby, instance, return …)
     */
    CompletableFuture<MoveResult> requestMove(UUID player, String destination, String reason);

    /**
     * Sends to the lobby (harbor-infra.yml lobby_server, proposed). No automatic departure on
     * arrival; if the player left the main server, they get a seat grace period.
     */
    CompletableFuture<MoveResult> requestLobby(UUID player, String reason);

    /**
     * Puts a player in the lobby into the main server queue (same as /출항; for NPC, GUI, Skript;
     * proposed). The proxy notifies the player of the result. Ignored if the player is not in the
     * lobby.
     */
    void requestDepart(UUID player);

    /**
     * Sends a party to an instance server ('17-4' 10.4 step 3). Boss assigns the server via
     * harbor:instance beforehand and passes the assigned server ID here. On partial failure
     * ok=false, reason = {@code partial:<succeeded>/<total>:<uuid>=<reason>;…}.
     *
     * @param requestId matching the assignment ID makes logs easier to correlate. If empty, a new
     *     one is generated
     */
    CompletableFuture<MoveResult> requestInstance(
            List<UUID> party, String assignedServer, String requestId);
}
