package wiki.creeper.harbor.web.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 토큰마다 1분 창 요청 수 제한 (서버 API 없음). 스레드 안전. */
public final class RateLimiter {

    private record Window(long minute, int count) {}

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    /**
     * @param nowMillis 지금 시각 (테스트용)
     */
    public boolean allow(String key, long nowMillis) {
        if (perMinute <= 0) {
            return true;
        }
        long minute = nowMillis / 60_000;
        Window next =
                windows.compute(
                        key,
                        (k, w) ->
                                w == null || w.minute() != minute
                                        ? new Window(minute, 1)
                                        : new Window(minute, w.count() + 1));
        return next.count() <= perMinute;
    }
}
