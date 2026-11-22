package wiki.creeper.harbor.season.transition;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.ResetPlan;

/**
 * Builds season transition SQL (pure computation). Identifiers are format-checked and then wrapped
 * in backticks. keep_where is blocked from containing ; and comments by the content check.
 *
 * <p>Archives are created in the MariaDB schema archive_s&lt;n&gt; with the original table names
 * (docs 20 0.4).
 */
public final class ArchiveSql {

    private static final Pattern IDENT = Pattern.compile("[a-z0-9_]{1,64}");

    private ArchiveSql() {}

    public static String ident(String name) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("SQL 식별자 형식이 틀렸습니다: " + name);
        }
        return "`" + name + "`";
    }

    public static String archiveSchema(int season) {
        return "archive_s" + season;
    }

    public static String createSchema(int season) {
        return "CREATE DATABASE IF NOT EXISTS "
                + ident(archiveSchema(season))
                + " CHARACTER SET utf8mb4";
    }

    public static String dropSchema(int season) {
        return "DROP DATABASE IF EXISTS " + ident(archiveSchema(season));
    }

    public static String qualified(@Nullable String schema, String table) {
        return schema == null ? ident(table) : ident(schema) + "." + ident(table);
    }

    public static String createLike(int season, String table) {
        return "CREATE TABLE IF NOT EXISTS "
                + qualified(archiveSchema(season), table)
                + " LIKE "
                + ident(table);
    }

    /** Season-n row condition for this table. For full scope, all rows (no parameters). */
    public static String where(ResetPlan.TableRule rule) {
        return rule.scope() == ResetPlan.Scope.SEASON_COLUMN
                ? ident(rule.column()) + " = ?"
                : "1 = 1";
    }

    public static boolean hasSeasonParam(ResetPlan.TableRule rule) {
        return rule.scope() == ResetPlan.Scope.SEASON_COLUMN;
    }

    /** Row count and CRC32 sum. Column order is information_schema ORDINAL_POSITION. */
    public static String checksum(
            @Nullable String schema, ResetPlan.TableRule rule, List<String> columns) {
        String concat =
                columns.stream()
                        .map(c -> "IFNULL(" + ident(c) + ", '\\\\N')")
                        .collect(Collectors.joining(", "));
        return "SELECT COUNT(*), COALESCE(SUM(CRC32(CONCAT_WS('|', "
                + concat
                + "))), 0) FROM "
                + qualified(schema, rule.table())
                + " WHERE "
                + where(rule);
    }

    /** One batch of a chunked copy (ORDER BY primary key + LIMIT/OFFSET). */
    public static String copyBatch(int season, ResetPlan.TableRule rule, List<String> orderBy) {
        String order =
                orderBy.isEmpty()
                        ? ""
                        : " ORDER BY "
                                + orderBy.stream()
                                        .map(ArchiveSql::ident)
                                        .collect(Collectors.joining(", "));
        return "INSERT INTO "
                + qualified(archiveSchema(season), rule.table())
                + " SELECT * FROM "
                + ident(rule.table())
                + " WHERE "
                + where(rule)
                + order
                + " LIMIT ? OFFSET ?";
    }

    /** Drops a half-finished copy left in the archive. */
    public static String clearArchive(int season, ResetPlan.TableRule rule) {
        return "DELETE FROM "
                + qualified(archiveSchema(season), rule.table())
                + " WHERE "
                + where(rule);
    }

    /** RESET delete condition (keep_where rows are kept). */
    public static String resetWhere(ResetPlan.TableRule rule) {
        String keep = rule.keepWhere();
        String base = where(rule);
        return keep == null ? base : base + " AND NOT (" + keep + ")";
    }

    /** One batch of a chunked RESET delete. */
    public static String deleteBatch(ResetPlan.TableRule rule) {
        return "DELETE FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule) + " LIMIT ?";
    }

    /** dry-run: number of rows to delete. */
    public static String countReset(ResetPlan.TableRule rule) {
        return "SELECT COUNT(*) FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule);
    }

    /** For checking keep_where syntax. */
    public static String explainKeep(ResetPlan.TableRule rule) {
        return "EXPLAIN SELECT 1 FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule);
    }

    /** Chunked update of fish_ledger status to ARCHIVED. */
    public static String archiveFishLedger(int states) {
        String marks = String.join(", ", Collections.nCopies(states, "?"));
        return "UPDATE fish_ledger SET state = 'ARCHIVED' WHERE season = ? AND state IN ("
                + marks
                + ") LIMIT ?";
    }
}
