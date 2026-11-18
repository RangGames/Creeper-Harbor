package wiki.creeper.harbor.core.api;

import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.ResourcePackIndex;
import wiki.creeper.harbor.common.pack.PublishedPack;

/**
 * 지금 배포 중인 리소스팩의 모델·소리 목록. 자동 빌드로 올린 팩({@link PublishedPack}, Redis)이 있으면 그것을, 없으면 콘텐츠의
 * resource_pack.yml을 본다. {@link ItemModels}·{@link HarborSounds}가 쓴다.
 */
public final class ActivePack {

    private record State(PublishedPack pack, Set<String> models, Set<String> sounds) {}

    private static volatile @Nullable State state;

    private ActivePack() {}

    /** 자동 빌드로 배포 중인 팩 (없으면 빈 값). */
    public static Optional<PublishedPack> published() {
        State s = state;
        return s == null ? Optional.empty() : Optional.of(s.pack());
    }

    /** Core 내부용: 새 팩을 알게 됐을 때. */
    public static void publish(@Nullable PublishedPack pack) {
        state =
                pack == null
                        ? null
                        : new State(pack, Set.copyOf(pack.models()), Set.copyOf(pack.sounds()));
    }

    public static boolean hasModel(ContentSnapshot snapshot, String model) {
        State s = state;
        if (s != null) {
            return s.models().contains(model);
        }
        return !snapshot.typeNames().contains(ResourcePackIndex.TYPE.name())
                || snapshot.single(ResourcePackIndex.TYPE).hasModel(model);
    }

    public static boolean hasSound(ContentSnapshot snapshot, String sound) {
        State s = state;
        if (s != null) {
            return s.sounds().contains(sound);
        }
        return !snapshot.typeNames().contains(ResourcePackIndex.TYPE.name())
                || snapshot.single(ResourcePackIndex.TYPE).hasSound(sound);
    }
}
