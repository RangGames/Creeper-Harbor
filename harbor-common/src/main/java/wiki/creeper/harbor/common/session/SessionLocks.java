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
 * <p>프로토콜 ('16. 플러그인 설계' 4.5):
 *
 * <ol>
 *   <li>Proxy가 출발 서버에 이동을 요청한다 ({@link SessionProtocol#HANDOFF_REQUEST})
 *   <li>출발 서버가 플레이어 데이터를 즉시 저장한다
 *   <li>출발 서버가 잠금을 handoff 상태로 바꾸고 ({@link #beginHandoff}) 완료를 알린다
 *   <li>Proxy가 도착 서버로 접속을 넘긴다
 *   <li>도착 서버가 handoff 상태를 확인하고 소유권을 가져온다 ({@link #awaitClaim})
 *   <li>도착 서버가 플레이어 데이터를 로드한다
 * </ol>
 *
 * 모든 상태 변경은 Lua 스크립트로 비교 후 교체(CAS)한다. 모든 메서드는 블로킹이다.
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
              redis.call('set', KEYS[1], 'OWNED|' .. ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if v == 'OWNED|' .. ARGV[1] then
              redis.call('pexpire', KEYS[1], ARGV[2])
              return 3
            end
            local to = string.match(v, '^HANDOFF|[^|]+|(.+)$')
            if to == ARGV[1] then
              redis.call('set', KEYS[1], 'OWNED|' .. ARGV[1], 'PX', ARGV[2])
              return 2
            end
            return 0
            """;

    // ARGV[1] = 출발, ARGV[2] = 도착, ARGV[3] = TTL(ms)
    private static final String BEGIN_HANDOFF =
            """
            if redis.call('get', KEYS[1]) == 'OWNED|' .. ARGV[1] then
              redis.call('set', KEYS[1], 'HANDOFF|' .. ARGV[1] .. '|' .. ARGV[2], 'PX', ARGV[3])
              return 1
            end
            return 0
            """;

    // ARGV[1] = 출발, ARGV[2] = TTL(ms). 아직 도착 서버가 가져가지 않았을 때만 되돌린다
    private static final String CANCEL_HANDOFF =
            """
            local v = redis.call('get', KEYS[1])
            if v and string.sub(v, 1, string.len('HANDOFF|' .. ARGV[1] .. '|')) == 'HANDOFF|' .. ARGV[1] .. '|' then
              redis.call('set', KEYS[1], 'OWNED|' .. ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """;

    private static final String RELEASE =
            """
            if redis.call('get', KEYS[1]) == 'OWNED|' .. ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;

    // KEYS = 여러 플레이어. ARGV[1] = 서버, ARGV[2] = TTL(ms). 결과: 연장한 수
    private static final String REFRESH =
            """
            local n = 0
            for i, key in ipairs(KEYS) do
              if redis.call('get', key) == 'OWNED|' .. ARGV[1] then
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

    /** 출발 서버: 저장을 마친 뒤 잠금을 handoff 상태로 바꾼다. 내 잠금이 아니면 false. */
    public boolean beginHandoff(UUID player, String from, String to) {
        try {
            @Nullable Long result = eval(BEGIN_HANDOFF, new String[] {key(player)}, from, to, ttl());
            return result != null && result == 1L;
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "handoff 전환 실패 (" + player + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * 출발 서버: handoff 후에도 플레이어가 그대로 남아 있으면 (프록시가 이동을 취소함) 소유권을 되찾는다. 도착 서버가 이미 가져갔으면
     * false.
     */
    public boolean cancelHandoff(UUID player, String from) {
        try {
            @Nullable Long result = eval(CANCEL_HANDOFF, new String[] {key(player)}, from, ttl());
            return result != null && result == 1L;
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "handoff 취소 실패 (" + player + "): " + e.getMessage());
            return false;
        }
    }

    /** 퇴장: 내 잠금이면 지운다. handoff 중이거나 다른 서버 것이면 그대로 둔다. */
    public boolean release(UUID player, String server) {
        try {
            @Nullable Long result = eval(RELEASE, new String[] {key(player)}, server);
            return result != null && result == 1L;
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "세션 잠금 해제 실패 (" + player + "), 30초 뒤 만료됩니다: " + e.getMessage());
            return false;
        }
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

    private @Nullable Long eval(String script, String[] keys, String... args) {
        return redis.sync().eval(script, ScriptOutputType.INTEGER, keys, args);
    }

    private static String ttl() {
        return String.valueOf(TTL.toMillis());
    }
}
