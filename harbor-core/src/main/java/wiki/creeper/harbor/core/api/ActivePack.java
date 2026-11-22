package wiki.creeper.harbor.core.api;

import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.ResourcePackIndex;
import wiki.creeper.harbor.common.pack.PublishedPack;

/**
 * Model and sound lists of the resource pack currently being served. Uses the auto-built pack
 * ({@link PublishedPack}, Redis) if there is one, otherwise the content's resource_pack.yml. Used
 * by {@link ItemModels} and {@link HarborSounds}.
 */
public final class ActivePack {

    private record State(PublishedPack pack, Set<String> models, Set<String> sounds) {}

    private static volatile @Nullable State state;

    private ActivePack() {}

    /** The auto-built pack currently being served (empty if none). */
    public static Optional<PublishedPack> published() {
        State s = state;
        return s == null ? Optional.empty() : Optional.of(s.pack());
    }

    /** Core internal: called when a new pack becomes known. */
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
