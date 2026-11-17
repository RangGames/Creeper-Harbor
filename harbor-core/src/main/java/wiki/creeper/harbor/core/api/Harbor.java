package wiki.creeper.harbor.core.api;

import org.jspecify.annotations.Nullable;

/** Core API 진입점. {@code Harbor.api()} 하나로 모든 서비스에 접근한다. */
public final class Harbor {

    private static volatile @Nullable HarborApi instance;

    private Harbor() {}

    /** Core가 켜진 뒤에만 쓸 수 있다. 다른 모듈은 paper-plugin.yml에서 Harbor-Core에 의존하므로 항상 켜져 있다. */
    public static HarborApi api() {
        HarborApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Harbor-Core가 아직 켜지지 않았습니다");
        }
        return api;
    }

    /** Core 내부용. */
    public static void install(@Nullable HarborApi api) {
        instance = api;
    }
}
