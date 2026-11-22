package wiki.creeper.harbor.npc.cutscene;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.content.Spot;
import wiki.creeper.harbor.npc.data.NpcRepository;
import wiki.creeper.harbor.npc.data.RestoreInfo;

/**
 * Persistence of cutscene restore info (cutscene_restore): ordered per-player writes, loading
 * before login, and putting the player back after a quit or crash.
 */
final class CutsceneRestore {

    private final HarborApi api;
    private final Worlds worlds;
    private final Logger log;

    /** Restore info read before login (AsyncPlayerPreLoginEvent → PlayerJoinEvent). */
    private final Map<UUID, RestoreInfo> pendingRestore = new ConcurrentHashMap<>();

    /**
     * Per-player ordering of restore-row writes (if a save and a delete cross, the leftover row
     * drags the player back to the cutscene start position on the next join).
     */
    private final Map<UUID, CompletableFuture<Void>> restoreWrites = new ConcurrentHashMap<>();

    /**
     * Runs after this player's previous restore-row write finishes (keeps order only, regardless of
     * success or failure).
     */
    CompletableFuture<Void> write(UUID uuid, SqlConsumer work) {
        CompletableFuture<Void> next =
                restoreWrites
                        .getOrDefault(uuid, CompletableFuture.completedFuture(null))
                        .handle((v, e) -> null)
                        .thenCompose(ignored -> api.database().asyncVoid(work));
        restoreWrites.put(uuid, next);
        var _ = next.whenComplete((v, e) -> restoreWrites.remove(uuid, next));
        return next;
    }

    CutsceneRestore(HarborApi api, Worlds worlds, Logger log) {
        this.api = api;
        this.worlds = worlds;
        this.log = log;
    }

    /**
     * @param samePlace whether to restore the location too (game mode only for a cutscene started
     *     on another server)
     * @param end the cutscene's end location (the original location if absent)
     */
    void restorePlayer(Player player, RestoreInfo info, boolean samePlace, @Nullable Spot end) {
        player.setSpectatorTarget(null);
        GameMode mode;
        try {
            mode = GameMode.valueOf(info.gameMode());
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        player.setGameMode(mode);
        player.setAllowFlight(info.allowFlight() || mode == GameMode.CREATIVE);
        player.setFlying(info.flying() && player.getAllowFlight());
        player.resetPlayerTime();
        player.resetPlayerWeather();
        if (samePlace) {
            Location target = null;
            if (end != null) {
                target = worlds.location(end).orElse(null);
            }
            if (target == null) {
                World w = Bukkit.getWorld(info.world());
                if (w != null) {
                    target =
                            new Location(w, info.x(), info.y(), info.z(), info.yaw(), info.pitch());
                }
            }
            if (target != null) {
                player.teleport(target);
            }
        }
    }

    /** Reads restore info before login (so the main thread never reads the DB). */
    void preload(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        // read only after the previous session's restore-row write · delete finishes (otherwise the
        // not-yet-deleted row is read and the player is dragged to the cutscene start position)
        CompletableFuture<Void> previous = restoreWrites.get(uuid);
        if (previous != null) {
            try {
                previous.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException e) {
                log.warn("컷씬 복구 행 정리를 기다리지 못했습니다 ({})", uuid);
            }
        }
        try {
            api.database()
                    .tx(c -> NpcRepository.loadRestore(c, uuid))
                    .ifPresentOrElse(
                            info -> pendingRestore.put(uuid, info),
                            () -> pendingRestore.remove(uuid));
        } catch (java.sql.SQLException e) {
            log.error("컷씬 복구 정보를 읽지 못했습니다 ({})", uuid, e);
        }
    }

    /** First join after a crash: puts back the state saved on this server. */
    void join(Player player) {
        RestoreInfo info = pendingRestore.remove(player.getUniqueId());
        if (info != null) {
            apply(player, info);
        }
    }

    void apply(Player player, RestoreInfo info) {
        boolean samePlace = info.server().equals(api.infra().serverId());
        if (!samePlace) {
            // game mode lives in each server's own player file. Applying and deleting on another
            // server
            // would leave no row on the origin server, where spectator mode was saved, so the
            // player
            // would stay in spectator mode (and this server would get the origin server's game
            // mode).
            // Keep it until the player joins the origin server
            log.info(
                    "{}의 중단된 컷씬 {} 복구는 원래 서버({})에서 합니다",
                    player.getName(),
                    info.cutscene(),
                    info.server());
            return;
        }
        restorePlayer(player, info, true, null);
        UUID uuid = player.getUniqueId();
        var _ = write(uuid, c -> NpcRepository.deleteRestore(c, uuid));
        log.info(
                "{}의 중단된 컷씬 {} 상태를 복구했습니다 (위치 복구: {})",
                player.getName(),
                info.cutscene(),
                samePlace);
    }
}
