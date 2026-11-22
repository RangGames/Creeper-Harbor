package wiki.creeper.harbor.common.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Distributed lock using SET NX PX. For jobs only one server may do, like world bosses, market
 * price updates and ledger audits. Blocking calls.
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

    /** Tries to acquire the lock. Empty Optional if another server holds it. */
    public Optional<Handle> tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String ok = redis.sync().set(key, token, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(ok) ? Optional.of(new Handle(key, token)) : Optional.empty();
    }

    /**
     * An acquired lock. Released or extended only when the token matches (so a lock re-acquired by
     * another server is not deleted).
     */
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
                            .eval(
                                    EXTEND,
                                    ScriptOutputType.INTEGER,
                                    new String[] {key},
                                    token,
                                    String.valueOf(ttl.toMillis()));
            return result != null && result == 1L;
        }

        public boolean release() {
            Long result =
                    redis.sync().eval(RELEASE, ScriptOutputType.INTEGER, new String[] {key}, token);
            return result != null && result == 1L;
        }

        @Override
        public void close() {
            release();
        }
    }
}
