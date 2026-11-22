package wiki.creeper.harbor.common.redis;

/** Redis connection settings. E.g. {@code redis://:password@localhost:6379/0}. */
public record RedisConfig(String uri) {

    @Override
    public String toString() {
        // May contain a password, so never print it as-is
        return "RedisConfig[" + uri.replaceAll("//[^@/]*@", "//***@") + "]";
    }
}
