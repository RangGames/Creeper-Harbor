package wiki.creeper.harbor.season.transition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** information_schema 읽기 (지금 DB = DATABASE()). */
public final class SchemaInspector {

    private SchemaInspector() {}

    /** season 열이 있는 테이블 전부 (reset_plan 검사, docs 20 11.3). */
    public static Set<String> seasonTables(Connection c) throws SQLException {
        Set<String> result = new TreeSet<>();
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'season'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    public static boolean tableExists(Connection c, @Nullable String schema, String table)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = "
                                + (schema == null ? "DATABASE()" : "?")
                                + " AND TABLE_NAME = ?")) {
            int i = 1;
            if (schema != null) {
                ps.setString(i++, schema);
            }
            ps.setString(i, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static boolean schemaExists(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 열 이름 (정의 순서). */
    public static List<String> columns(Connection c, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    /** 기본 키 열 (없으면 빈 목록). */
    public static List<String> primaryKey(Connection c, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY'"
                                + " ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    /** 지금 DB 크기 (데이터 + 인덱스, 바이트). */
    public static long databaseBytes(Connection c) throws SQLException {
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES"
                                        + " WHERE TABLE_SCHEMA = DATABASE()");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
