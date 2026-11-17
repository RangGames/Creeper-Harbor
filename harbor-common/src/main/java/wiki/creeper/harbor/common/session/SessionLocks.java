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
 * 서버 이동 세션 잠금 ({@code harbor:lock:<uuid>}, 30초 만료). Proxy와 Core가 같은 코드를 쓴다.
 *
 * <p>프로토콜 ('16. 플러그인 설계' 4.5, docs 20 1.4 MoveCoordinator):
 *
 * <ol>
 *   <li>백엔드가 MoveRequest를 프록시에 보낸다
 *   <li>프록시가 출발 서버에 TransferPrepare를 보낸다
 *   <li>출발 서버가 즉시 저장하고 잠금을 {@code handoff:<도착>:<요청 ID>}로 바꾼 뒤 ({@link #beginHandoff})
 *       TransferReady를 보낸다
 *   <li>프록시가 도착 서버로 접속을 넘긴다. 실패하면 TransferAbort → 출발 서버가 잠금을 되돌린다 ({@link #abortHandoff})
 *   <li>도착 서버가 handoff 상태를 확인하고 소유권을 가져온 뒤 ({@link #awaitClaim}) 데이터를 로드한다
 * </ol>
 *
 * 로비는 게임 데이터가 없어서 잠금을 잡지 않는다. 모든 상태 변경은 Lua 스크립트로 비교 후 교체(CAS)한다. 모든 메서드는 블로킹이다.
 */
public final class SessionLocks {

    public static final Duration TTL = Duration.ofSeconds(30);
    public static final Duration HANDOFF_WAIT = Duration.ofSeconds(5);
    public static final String KEY_PREFIX = "harbor:lock:";

    private static final System.Logger LOG = System.getLogger("Harbor/Session");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]+");
    private static final long POLL_MILLIS = 200;

    // ARGV[1] = 서버, ARGV[2] = TTL(ms). 결과: 1 = 새로 가져옴, 2 = handoff 인수, 3 = 이미 내 것, 0 = 다른 서버
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

    // ARGV[1] = 출발, ARGV[2] = 도착, ARGV[3] = 요청 ID, ARGV[4] = TTL(ms)
    private static final String BEGIN_HANDOFF =
            """
            if redis.call('get', KEYS[1]) == 'owned:' .. ARGV[1] then
              redis.call('set', KEYS[1], 'handoff:' .. ARGV[2] .. ':' .. ARGV[3], 'PX', ARGV[4])
              return 1
            end
            return 0
            """;

    // ARGV[1] = 출발, ARGV[2] = 요청 ID, ARGV[3] = TTL(ms). 도착 서버가 아직 가져가지 않았을 때만 되돌린다
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

    // ARGV[1] = 요청 ID. 이동 중 접속이 끊기면 그 요청의 handoff 잠금만 지운다
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

    // KEYS = 여러 플레이어. ARGV[1] = 서버, ARGV[2] = TTL(ms). 결과: 연장한 수
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

    /** 한 번 시도한다. */
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
     * 잠금을 가져올 때까지 기다린다. 다른 서버가 handoff를 마칠 시간을 주되, timeout 안에 못 가져오면 BUSY. 접속 전 이벤트
     * (AsyncPlayerPreLoginEvent)처럼 블로킹해도 되는 스레드에서만 부른다.
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

    /** 출발 서버: 저장을 마친 뒤 잠금을 {@code handoff:<도착>:<요청 ID>}로 바꾼다. 내 잠금이 아니면 false. */
    public boolean beginHandoff(UUID player, String from, String to, String requestId) {
        return cas(BEGIN_HANDOFF, player, "handoff 전환", from, to, requestId, ttl());
    }

    /** 출발 서버: TransferAbort를 받으면 잠금을 owned로 되돌린다. 도착 서버가 이미 가져갔으면 false. */
    public boolean abortHandoff(UUID player, String from, String requestId) {
        return cas(ABORT_HANDOFF, player, "handoff 취소", from, requestId, ttl());
    }

    /** 출발 서버: 이동 중 접속이 끊기면 그 요청의 handoff 잠금만 지운다 (이미 저장했으므로 추가 저장 없음). */
    public boolean releaseHandoff(UUID player, String requestId) {
        return cas(RELEASE_HANDOFF, player, "handoff 해제", requestId);
    }

    /** 퇴장: 내 잠금(owned)이면 지운다. handoff 중이거나 다른 서버 것이면 그대로 둔다. */
    public boolean release(UUID player, String server) {
        return cas(RELEASE, player, "세션 잠금 해제", server);
    }

    /** 접속 중인 플레이어들의 잠금을 연장한다 (만료 30초보다 짧은 주기로 부른다). */
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
