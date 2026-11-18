package wiki.creeper.harbor.season.transition;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.ResetPlan;

/**
 * 시즌 전환 SQL 만들기 (순수 계산). 식별자는 형식을 검사한 뒤 백틱으로 감싼다. keep_where는 콘텐츠 검사에서 ; 와 주석을 막는다.
 *
 * <p>아카이브는 MariaDB 스키마 archive_s&lt;n&gt;에 원래 테이블 이름 그대로 만든다 (docs 20 0.4).
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

    /** 이 테이블의 시즌 n 행 조건. full 범위면 전체 (매개변수 없음). */
    public static String where(ResetPlan.TableRule rule) {
        return rule.scope() == ResetPlan.Scope.SEASON_COLUMN
                ? ident(rule.column()) + " = ?"
                : "1 = 1";
    }

    public static boolean hasSeasonParam(ResetPlan.TableRule rule) {
        return rule.scope() == ResetPlan.Scope.SEASON_COLUMN;
    }

    /** 행 수와 CRC32 합계. 열 순서는 information_schema ORDINAL_POSITION. */
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

    /** 분할 복사 한 묶음 (ORDER BY 기본 키 + LIMIT/OFFSET). */
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

    /** 아카이브에 남은 반쪽 복사본 지우기. */
    public static String clearArchive(int season, ResetPlan.TableRule rule) {
        return "DELETE FROM "
                + qualified(archiveSchema(season), rule.table())
                + " WHERE "
                + where(rule);
    }

    /** RESET 삭제 조건 (keep_where 행은 남긴다). */
    public static String resetWhere(ResetPlan.TableRule rule) {
        String keep = rule.keepWhere();
        String base = where(rule);
        return keep == null ? base : base + " AND NOT (" + keep + ")";
    }

    /** RESET 분할 삭제 한 묶음. */
    public static String deleteBatch(ResetPlan.TableRule rule) {
        return "DELETE FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule) + " LIMIT ?";
    }

    /** dry-run: 지울 행 수. */
    public static String countReset(ResetPlan.TableRule rule) {
        return "SELECT COUNT(*) FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule);
    }

    /** keep_where 문법 검사용. */
    public static String explainKeep(ResetPlan.TableRule rule) {
        return "EXPLAIN SELECT 1 FROM " + ident(rule.table()) + " WHERE " + resetWhere(rule);
    }

    /** fish_ledger 상태 ARCHIVED 분할 갱신. */
    public static String archiveFishLedger(int states) {
        String marks = String.join(", ", Collections.nCopies(states, "?"));
        return "UPDATE fish_ledger SET state = 'ARCHIVED' WHERE season = ? AND state IN ("
                + marks
                + ") LIMIT ?";
    }
}
