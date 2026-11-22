package wiki.creeper.harbor.common.db;

import java.sql.Connection;
import java.sql.SQLException;

/** DB operation with no result. */
@FunctionalInterface
public interface SqlConsumer {
    void accept(Connection connection) throws SQLException;
}
