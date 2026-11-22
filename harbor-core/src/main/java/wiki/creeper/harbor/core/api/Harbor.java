package wiki.creeper.harbor.core.api;

import org.jspecify.annotations.Nullable;

/** Core API entry point. {@code Harbor.api()} gives access to every service. */
public final class Harbor {

    private static volatile @Nullable HarborApi instance;

    private Harbor() {}

    /**
     * Usable only after Core is enabled. Other modules depend on Harbor-Core in paper-plugin.yml,
     * so it is always enabled for them.
     */
    public static HarborApi api() {
        HarborApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Harbor-Core가 아직 켜지지 않았습니다");
        }
        return api;
    }

    /** Core internal. */
    public static void install(@Nullable HarborApi api) {
        instance = api;
    }
}
