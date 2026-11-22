package wiki.creeper.harbor.common.session;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * Server-move session lock ({@code harbor:lock:<uuid>}, 30-second expiry). Proxy and Core share
 * this code.
 *
 * <p>Protocol ('16. Plugin design' 4.5, docs 20 1.4 MoveCoordinator):
 *
 * <ol>
 *   <li>The backend sends a MoveRequest to the proxy
 *   <li>The proxy sends TransferPrepare to the source server
 *   <li>The source server saves immediately, switches the lock to {@code
 *       handoff:<destination>:<request ID>} ({@link #beginHandoff}), then sends TransferReady
 *   <li>The proxy hands the connection to the destination server. On failure, TransferAbort → the
 *       source server reverts the lock ({@link #abortHandoff})
 *   <li>The destination server confirms the handoff state, takes ownership ({@link #awaitClaim}),
 *       then loads the data
 * </ol>
 *
 * Every backend, including the lobby, takes the lock (inventory sync, proposed). Every state change
 * is a compare-and-swap (CAS) in a Lua script. All methods are blocking.
 */
public final class SessionLocks {

    public static final Duration TTL = Duration.ofSeconds(30);
    public static final Duration HANDOFF_WAIT = Duration.ofSeconds(5);
    public static final String KEY_PREFIX = "harbor:lock:";

    private static final System.Logger LOG = System.getLogger("Harbor/Session");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]+");
    private static final long POLL_MILLIS = 200;

    // ARGV[1] = server, ARGV[2] = TTL (ms). Result: 1 = newly taken, 2 = handoff taken over, 3 =
    // already mine, 0 = another server
    private static final String CLAIM =
            """
            local v = redis.call('get', KEYS[1])
            if not v then
              redis.call('set', KEYS[1], 'owned:' .. ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if v == 'owned:' .. ARGV[1] then
              redis.call('pexpire', KEYS[1], ARGV[2])
              return 3
            end
            local prefix = 'handoff:' .. ARGV[1] .. ':'
            if string.sub(v, 1, string.len(prefix)) == prefix then
              redis.call('set', KEYS[1], 'owned:' .. ARGV[1], 'PX', ARGV[2])
              return 2
            end
            return 0
            """;

    // ARGV[1] = source, ARGV[2] = destination, ARGV[3] = request ID, ARGV[4] = TTL (ms)
    private static final String BEGIN_HANDOFF =
            """
            if redis.call('get', KEYS[1]) == 'owned:' .. ARGV[1] then
              redis.call('set', KEYS[1], 'handoff:' .. ARGV[2] .. ':' .. ARGV[3], 'PX', ARGV[4])
              return 1
            end
            return 0
            """;

    // ARGV[1] = source, ARGV[2] = request ID, ARGV[3] = TTL (ms). Reverts only if the destination
    // server has not taken it yet
    private static final String ABORT_HANDOFF =
            """
            local v = redis.call('get', KEYS[1])
            local suffix = ':' .. ARGV[2]
            if v and string.sub(v, 1, 8) == 'handoff:' and string.sub(v, -string.len(suffix)) == suffix then
              redis.call('set', KEYS[1], 'owned:' .. ARGV[1], 'PX', ARGV[3])
              return 1
            end
            return 0
            """;

    // ARGV[1] = request ID. If the connection drops mid-move, deletes only that request's handoff
    // lock
    private static final String RELEASE_HANDOFF =
            """
            local v = redis.call('get', KEYS[1])
            local suffix = ':' .. ARGV[1]
            if v and string.sub(v, 1, 8) == 'handoff:' and string.sub(v, -string.len(suffix)) == suffix then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;

    private static final String RELEASE =
            """
            if redis.call('get', KEYS[1]) == 'owned:' .. ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;

    // KEYS = several players. ARGV[1] = server, ARGV[2] = TTL (ms). Result: number extended
    private static final String REFRESH =
            """
            local n = 0
            for i, key in ipairs(KEYS) do
              if redis.call('get', key) == 'owned:' .. ARGV[1] then
                redis.call('pexpire', key, ARGV[2])
                n = n + 1
              end
            end
            return n
            """;

    private final Redis redis;

    public SessionLocks(Redis redis) {
        this.redis = redis;
    }

    public static String key(UUID player) {
        return KEY_PREFIX + player;
    }

    public static void requireServerId(String server) {
        if (!SERVER_ID.matcher(server).matches()) {
            throw new IllegalArgumentException("서버 ID는 소문자·숫자·-·_만 쓸 수 있습니다: " + server);
        }
    }

    /** Tries once. */
    public ClaimResult claim(UUID player, String server) {
        try {
            @Nullable Long result = eval(CLAIM, new String[] {key(player)}, server, ttl());
            if (result == null) {
                return ClaimResult.UNAVAILABLE;
            }
            return switch (result.intValue()) {
                case 1, 3 -> ClaimResult.CLAIMED;
                case 2 -> ClaimResult.HANDOFF_CLAIMED;
                default -> ClaimResult.BUSY;
            };
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "세션 잠금 실패 (" + player + "): " + e.getMessage());
            return ClaimResult.UNAVAILABLE;
        }
    }

    /**
     * Waits until the lock is taken. Gives another server time to finish its handoff, but returns
     * BUSY if it cannot be taken within timeout. Call only on threads that may block, like the
     * pre-login event (AsyncPlayerPreLoginEvent).
     */
    public ClaimResult awaitClaim(UUID player, String server, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            ClaimResult result = claim(player, server);
            if (result != ClaimResult.BUSY || System.nanoTime() >= deadline) {
                return result;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ClaimResult.UNAVAILABLE;
            }
        }
    }

    /**
     * Source server: after saving, switches the lock to {@code handoff:<destination>:<request ID>}.
     * false if the lock is not mine.
     */
    public boolean beginHandoff(UUID player, String from, String to, String requestId) {
        return cas(BEGIN_HANDOFF, player, "handoff 전환", from, to, requestId, ttl());
    }

    /**
     * Source server: on TransferAbort, reverts the lock to owned. false if the destination server
     * already took it.
     */
    public boolean abortHandoff(UUID player, String from, String requestId) {
        return cas(ABORT_HANDOFF, player, "handoff 취소", from, requestId, ttl());
    }

    /**
     * Source server: if the connection drops mid-move, deletes only that request's handoff lock
     * (already saved, so no extra save).
     */
    public boolean releaseHandoff(UUID player, String requestId) {
        return cas(RELEASE_HANDOFF, player, "handoff 해제", requestId);
    }

    /**
     * Quit: deletes the lock if it is mine (owned). Leaves it alone if in handoff or held by
     * another server.
     */
    public boolean release(UUID player, String server) {
        return cas(RELEASE, player, "세션 잠금 해제", server);
    }

    /** Extends the locks of online players (call more often than the 30-second expiry). */
    public int refresh(Collection<UUID> players, String server) {
        if (players.isEmpty()) {
            return 0;
        }
        String[] keys = players.stream().map(SessionLocks::key).toArray(String[]::new);
        try {
            @Nullable Long result = eval(REFRESH, keys, server, ttl());
            return result == null ? 0 : result.intValue();
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "세션 잠금 연장 실패: " + e.getMessage());
            return 0;
        }
    }

    public Optional<LockState> inspect(UUID player) {
        String raw = redis.sync().get(key(player));
        return raw == null ? Optional.empty() : LockState.decode(raw);
    }

    private boolean cas(String script, UUID player, String what, String... args) {
        try {
            @Nullable Long result = eval(script, new String[] {key(player)}, args);
            return result != null && result == 1L;
        } catch (RedisException e) {
            LOG.log(Level.WARNING, what + " 실패 (" + player + "): " + e.getMessage());
            return false;
        }
    }

    private @Nullable Long eval(String script, String[] keys, String... args) {
        return redis.sync().eval(script, ScriptOutputType.INTEGER, keys, args);
    }

    private static String ttl() {
        return String.valueOf(TTL.toMillis());
    }
}
