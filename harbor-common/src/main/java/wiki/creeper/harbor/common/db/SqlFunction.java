package wiki.creeper.harbor.common.db;

import java.sql.Connection;
import java.sql.SQLException;

/** DB operation on a single connection. */
@FunctionalInterface
public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
}
