package wiki.creeper.harbor.common.config;

import wiki.creeper.harbor.common.model.Keyed;

/** 서버 역할. 연결 풀 기본 크기가 다르다. */
public enum ServerRole implements Keyed {
    MAIN(20),
    LOBBY(8),
    INSTANCE(8),
    PROXY(5);

    private final int defaultPoolSize;

    ServerRole(int defaultPoolSize) {
        this.defaultPoolSize = defaultPoolSize;
    }

    public int defaultPoolSize() {
        return defaultPoolSize;
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
