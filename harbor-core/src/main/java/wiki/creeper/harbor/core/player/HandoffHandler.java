package wiki.creeper.harbor.core.player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferPrepare;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferReady;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Server move on the departing server (split out of {@link PlayerManager}): TransferPrepare →
 * handoff blockers → save → hand the lock off → TransferReady, and TransferAbort / stuck-handoff
 * rollback. Main thread unless noted.
 */
final class HandoffHandler {

    /**
     * Safety check in case the proxy's TransferAbort never arrives (longer than the proxy's 5s
     * limit).
     */
    private static final long STUCK_HANDOFF_TICKS = 15 * 20L;

    private final InfraConfig infra;
    private final WriteBehindQueue queue;
    private final SessionLocks locks;
    private final MessageBus bus;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;
    private final Map<UUID, PlayerState> online;
    private final Consumer<PlayerState> saveForHandoff;
    private final List<HandoffBlocker> handoffBlockers = new CopyOnWriteArrayList<>();

    HandoffHandler(
            InfraConfig infra,
            WriteBehindQueue queue,
            SessionLocks locks,
            MessageBus bus,
            MessageService messages,
            Tasks tasks,
            Logger log,
            Map<UUID, PlayerState> online,
            Consumer<PlayerState> saveForHandoff) {
        this.infra = infra;
        this.queue = queue;
        this.locks = locks;
        this.bus = bus;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
        this.online = online;
        this.saveForHandoff = saveForHandoff;
    }

    Runnable addHandoffBlocker(HandoffBlocker blocker) {
        handoffBlockers.add(blocker);
        return () -> handoffBlockers.remove(blocker);
    }

    void prepareTransfer(TransferPrepare request) {
        UUID uuid = request.player();
        PlayerState state = online.get(uuid);
        if (state == null || state.handingOff()) {
            String reason = state == null ? "not_online" : "already_moving";
            bus.publish(
                    ProxyMessages.toProxy(TransferReady.class),
                    new TransferReady(request.requestId(), uuid, false, reason));
            return;
        }
        // Module hooks: deny (e.g. during a cutscene) cancels right away, delay (trade or donation
        // in progress) waits up to 4 seconds
        Player player = Bukkit.getPlayer(uuid);
        List<CompletableFuture<?>> delays = new ArrayList<>();
        if (player != null) {
            for (HandoffBlocker blocker : handoffBlockers) {
                HandoffBlocker.Verdict verdict;
                try {
                    verdict = blocker.check(player, request.destination());
                } catch (RuntimeException e) {
                    log.error("이동 전 훅 오류 (무시하고 진행): {}", uuid, e);
                    continue;
                }
                switch (verdict) {
                    case HandoffBlocker.Deny(String reason) -> {
                        messages.send(player, "core.handoff.blocked." + reason);
                        bus.publish(
                                ProxyMessages.toProxy(TransferReady.class),
                                new TransferReady(
                                        request.requestId(), uuid, false, "blocked:" + reason));
                        return;
                    }
                    case HandoffBlocker.Delay(CompletableFuture<?> until) -> delays.add(until);
                    case HandoffBlocker.Allow() -> {}
                }
            }
        }
        state.setHandoffRequest(request.requestId());
        if (delays.isEmpty()) {
            beginTransfer(request, state);
            return;
        }
        var _ =
                CompletableFuture.allOf(delays.toArray(CompletableFuture[]::new))
                        .orTimeout(HandoffBlocker.MAX_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        // A failure of the work itself counts as finished; only a timeout is
                        // treated separately
                        .handle(
                                (ok, error) ->
                                        error != null
                                                && (error instanceof TimeoutException
                                                        || error.getCause()
                                                                instanceof TimeoutException))
                        .thenAcceptAsync(
                                timedOut -> {
                                    if (!request.requestId().equals(state.handoffRequest())
                                            || online.get(uuid) != state) {
                                        return;
                                    }
                                    if (timedOut) {
                                        // Saving while the trade is still running means the trade
                                        // may grant or take items after the snapshot, so they end
                                        // up nowhere or duplicated. Cancel the move and let the
                                        // trade finish
                                        state.setHandoffRequest(null);
                                        Player p = Bukkit.getPlayer(uuid);
                                        if (p != null) {
                                            messages.send(p, "core.handoff.blocked.busy");
                                        }
                                        bus.publish(
                                                ProxyMessages.toProxy(TransferReady.class),
                                                new TransferReady(
                                                        request.requestId(),
                                                        uuid,
                                                        false,
                                                        "blocked:busy"));
                                        return;
                                    }
                                    beginTransfer(request, state);
                                },
                                tasks.main());
    }

    private void beginTransfer(TransferPrepare request, PlayerState state) {
        UUID uuid = request.player();
        Sequence.cancelAll(uuid);
        saveForHandoff.accept(state);
        // Block actions from the snapshot on (HandoffFreeze). On cancel or failure clearHandoff
        // releases it
        state.markHandoffSaved();
        var _ =
                tasks.async(
                        () -> {
                            boolean ok = false;
                            String reason;
                            try {
                                queue.flushNow(PlayerManager.prefix(uuid));
                                ok =
                                        locks.beginHandoff(
                                                uuid,
                                                infra.serverId(),
                                                request.destination(),
                                                request.requestId());
                                reason = ok ? "ok" : "lock_lost";
                                boolean abortNow = false;
                                if (ok) {
                                    // Mark the lock as handed off and check for an abort request
                                    // atomically (so it can't interleave with abortTransfer)
                                    synchronized (state) {
                                        state.markHandoffLocked();
                                        abortNow = state.abortRequested();
                                    }
                                }
                                if (ok && state.left()) {
                                    // Disconnected while saving (e.g. proxy shutdown): the quit
                                    // side's release may have run first, so clear the leftover
                                    // handoff lock. The data was just written
                                    locks.releaseHandoff(uuid, request.requestId());
                                } else if (abortNow) {
                                    // The proxy cancelled the move while saving: restore the lock
                                    // and keep playing
                                    boolean restored =
                                            locks.abortHandoff(
                                                    uuid, infra.serverId(), request.requestId());
                                    tasks.main()
                                            .execute(
                                                    () -> {
                                                        if (restored) {
                                                            clearHandoff(
                                                                    state, request.requestId());
                                                        } else {
                                                            kickMoved(uuid);
                                                        }
                                                    });
                                    return;
                                }
                            } catch (SQLException e) {
                                log.error("서버 이동 저장 실패: {}", uuid, e);
                                reason = "save_failed";
                            }
                            bus.publish(
                                    ProxyMessages.toProxy(TransferReady.class),
                                    new TransferReady(request.requestId(), uuid, ok, reason));
                            if (ok) {
                                var _ =
                                        tasks.later(
                                                STUCK_HANDOFF_TICKS,
                                                () -> checkStuckHandoff(uuid, request.requestId()));
                            } else {
                                tasks.main()
                                        .execute(() -> clearHandoff(state, request.requestId()));
                            }
                        });
    }

    /** The proxy cancelled the move (TransferAbort): restore the lock and keep playing. */
    void abortTransfer(UUID uuid, String requestId) {
        PlayerState state = online.get(uuid);
        if (state == null || !requestId.equals(state.handoffRequest())) {
            return;
        }
        if (!state.handoffSaved()) {
            // Waiting on a handoff blocker delay: nothing saved or handed off yet. Clearing the
            // request stops the continuation after the delay
            clearHandoff(state, requestId);
            return;
        }
        synchronized (state) {
            if (!state.handoffLocked()) {
                // Saving (the lock still belongs to this server): must not kick now.
                // beginTransfer restores it after handing the lock off
                state.requestAbort();
                return;
            }
        }
        var _ =
                tasks.supplyAsync(() -> locks.abortHandoff(uuid, infra.serverId(), requestId))
                        .thenAcceptAsync(
                                restored -> {
                                    if (restored) {
                                        clearHandoff(state, requestId);
                                    } else {
                                        kickMoved(uuid);
                                    }
                                },
                                tasks.main());
    }

    /** No TransferAbort arrived but the player is still here: roll back on our own. */
    private void checkStuckHandoff(UUID uuid, String requestId) {
        PlayerState state = online.get(uuid);
        if (state != null && requestId.equals(state.handoffRequest())) {
            log.warn("서버 이동 응답이 없어 세션을 되돌립니다: {}", state.name());
            abortTransfer(uuid, requestId);
        }
    }

    private static void clearHandoff(PlayerState state, String requestId) {
        if (requestId.equals(state.handoffRequest())) {
            state.setHandoffRequest(null);
        }
    }

    /** Another server already took the data. Staying here risks duplication, so kick. */
    private void kickMoved(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.kick(messages.get("session.moved"));
        }
    }
}
