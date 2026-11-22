package wiki.creeper.harbor.core.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Data a module attaches to a player (e.g. Jobs skill allocation, Collection collection cache).
 * Core manages only when it is loaded and saved, not its contents.
 *
 * <p>Values are immutable objects (record, List.copyOf …). To change one, put a new value with
 * {@link HarborPlayer#set}. Saving happens on another thread, so mutating a value directly can mix
 * it with the value being saved.
 *
 * @param id name used in the save key (lowercase letters, digits, underscores)
 */
public record PlayerComponent<T>(String id, Loader<T> loader, Saver<T> saver) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public PlayerComponent {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("컴포넌트 ID 형식이 틀렸습니다: " + id);
        }
    }

    /** Called on a virtual thread before login. Returns the default value if there is no data. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(Connection connection, UUID player, SeasonId season) throws SQLException;
    }

    /** Called from the write-behind queue or the immediate save on quit. */
    @FunctionalInterface
    public interface Saver<T> {
        void save(Connection connection, UUID player, SeasonId season, T value) throws SQLException;
    }
}
