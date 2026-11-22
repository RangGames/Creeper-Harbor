package wiki.creeper.harbor.season.api;

import java.util.Optional;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.TypedId;

/**
 * Title ID (titles.yml, proposed). Example: {@code harbor:title/codex_25}.
 *
 * <p>{@link #of(String)} also accepts names without the prefix: {@code codex_25}, {@code
 * title/codex_25} and {@code harbor:title/codex_25} are all the same title (Collection's region
 * completion title {@code codex_region/coral_isles} can be passed as is).
 */
public record TitleId(HarborId id) implements TypedId {

    public static final String PREFIX = "title/";

    public TitleId {
        TypedId.requirePrefix(id, PREFIX);
    }

    /** IllegalArgumentException if malformed. */
    public static TitleId of(String raw) {
        HarborId id = HarborId.parse(raw);
        if (!id.hasPrefix(PREFIX)) {
            id = new HarborId(id.namespace(), PREFIX + id.path());
        }
        return new TitleId(id);
    }

    public static Optional<TitleId> tryOf(String raw) {
        try {
            return Optional.of(of(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The name after {@code title/} (for command display). */
    public String shortName() {
        return id.path().substring(PREFIX.length());
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
