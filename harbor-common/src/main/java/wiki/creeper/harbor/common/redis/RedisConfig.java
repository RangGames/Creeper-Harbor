package wiki.creeper.harbor.common.redis;

/** Redis 연결 설정. 예: {@code redis://:password@localhost:6379/0}. */
public record RedisConfig(String uri) {

    @Override
    public String toString() {
        // 비밀번호가 들어 있을 수 있어 그대로 출력하지 않는다
        return "RedisConfig[" + uri.replaceAll("//[^@/]*@", "//***@") + "]";
    }
}
