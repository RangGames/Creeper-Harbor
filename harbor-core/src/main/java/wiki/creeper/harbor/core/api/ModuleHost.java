package wiki.creeper.harbor.core.api;

import org.jspecify.annotations.Nullable;

/** Core internal: the side that handles module wiring. Modules don't use it directly. */
public interface ModuleHost {

    /** Wiring state of one module. */
    interface Handle {
        ModuleContext context();

        /** After enable() finishes: reloads content if any content types were added. */
        void finishEnable();

        void failEnable(RuntimeException cause);

        void detach();
    }

    Handle attach(HarborModule module);

    final class Holder {
        private static volatile @Nullable ModuleHost host;

        private Holder() {}
    }

    static ModuleHost get() {
        ModuleHost host = Holder.host;
        if (host == null) {
            throw new IllegalStateException("Harbor-Core가 아직 켜지지 않았습니다");
        }
        return host;
    }

    static void install(@Nullable ModuleHost host) {
        Holder.host = host;
    }
}
