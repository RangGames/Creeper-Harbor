package wiki.creeper.harbor.core.api;

import org.jspecify.annotations.Nullable;

/** Core 내부용: 모듈 연결을 처리하는 쪽. 모듈은 직접 쓰지 않는다. */
public interface ModuleHost {

    /** 모듈 하나의 연결 상태. */
    interface Handle {
        ModuleContext context();

        /** enable()이 끝난 뒤: 추가된 콘텐츠 형식이 있으면 다시 읽는다. */
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
