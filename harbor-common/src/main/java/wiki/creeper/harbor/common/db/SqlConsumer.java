package wiki.creeper.harbor.common.db;

import java.sql.Connection;
import java.sql.SQLException;

/** 결과가 없는 DB 작업. */
@FunctionalInterface
public interface SqlConsumer {
    void accept(Connection connection) throws SQLException;
}
