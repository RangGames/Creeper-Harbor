package wiki.creeper.harbor.common.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * SET NX PX 방식의 분산 잠금. 월드 보스·시세 갱신·원장 검사처럼 한 서버만 해야 하는 작업에 쓴다. 블로킹 호출이다.
 */
public final class DistributedLock {

    private static final String RELEASE =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final String EXTEND =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2])"
                    + " else return 0 end";

    private final Redis redis;

    public DistributedLock(Redis redis) {
        this.redis = redis;
    }

    /** 잠금을 시도한다. 다른 서버가 가지고 있으면 빈 Optional. */
    public Optional<Handle> tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String ok = redis.sync().set(key, token, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(ok) ? Optional.of(new Handle(key, token)) : Optional.empty();
    }

    /** 얻은 잠금. 토큰이 같을 때만 풀거나 연장한다 (다른 서버가 다시 얻은 잠금을 지우지 않게). */
    public final class Handle implements AutoCloseable {

        private final String key;
        private final String token;

        private Handle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        public String key() {
            return key;
        }

        public boolean extend(Duration ttl) {
            Long result =
                    redis.sync()
                            .eval(EXTEND, ScriptOutputType.INTEGER, new String[] {key}, token, String.valueOf(ttl.toMillis()));
            return result != null && result == 1L;
        }

        public boolean release() {
            Long result = redis.sync().eval(RELEASE, ScriptOutputType.INTEGER, new String[] {key}, token);
            return result != null && result == 1L;
        }

        @Override
        public void close() {
            release();
        }
    }
}
