package wiki.creeper.harbor.common.db;

import java.sql.Connection;
import java.sql.SQLException;

/** 연결 하나로 하는 DB 작업. */
@FunctionalInterface
public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
}
