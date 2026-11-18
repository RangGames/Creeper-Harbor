package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.session.LockState;
import wiki.creeper.harbor.common.session.SessionLocks;

/**
 * 이전 접속 정리 대기 (docs 20 1.4 "잠금이 남아 있을 때"). 직전 서버가 비정상 종료돼 세션 잠금이 살아 있으면(최대 30초) 대기열이 그 사람을 건너뛰게 하고,
 * 1초마다 잠금을 다시 봐서 풀리는 즉시 입장시킨다. 잠금 TTL보다 오래 기다리면 (잠금 연장이 계속되는 이상 상황) 표시를 풀고 본 서버 Core의 awaitClaim
 * 판단에 맡긴다.
 */
final class StaleLockWatcher {

    private static final Duration GIVE_UP = SessionLocks.TTL.plusSeconds(10);

    private final ProxyServer proxy;
    private final SessionLocks locks;
    private final QueueManager queue;
    private final String lobby;
    private final Logger log;
    private final Map<UUID, Instant> since = new ConcurrentHashMap<>();

    StaleLockWatcher(
            ProxyServer proxy, SessionLocks locks, QueueManager queue, String lobby, Logger log) {
        this.proxy = proxy;
        this.locks = locks;
        this.queue = queue;
        this.lobby = lobby;
        this.log = log;
    }

    /** 로그인 때 (가상 스레드): 남은 잠금이 있으면 표시한다. Redis 오류면 표시하지 않는다 (입장 때 Core가 다시 확인). */
    void checkAtLogin(UUID player) {
        if (held(player)) {
            since.put(player, Instant.now());
            queue.markCleaning(player);
        }
    }

    /** 1초마다 (스케줄러 스레드, Redis 동기 호출 가능). */
    void tick() {
        Instant now = Instant.now();
        for (UUID player : queue.cleaning()) {
            Instant start = since.getOrDefault(player, now);
            boolean offline = proxy.getPlayer(player).isEmpty();
            boolean timedOut = Duration.between(start, now).compareTo(GIVE_UP) > 0;
            if (offline || timedOut || !held(player)) {
                if (timedOut && !offline) {
                    log.warn("세션 잠금이 {}초 넘게 남아 있습니다: {}", GIVE_UP.toSeconds(), player);
                }
                since.remove(player);
                queue.clearCleaning(player);
            }
        }
    }

    /** 로비가 아닌 서버의 잠금이 남아 있는지. */
    private boolean held(UUID player) {
        try {
            Optional<LockState> state = locks.inspect(player);
            return state.isPresent()
                    && !(state.get() instanceof LockState.Owned(String server)
                            && server.equals(lobby));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
