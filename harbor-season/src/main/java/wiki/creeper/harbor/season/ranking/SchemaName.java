package wiki.creeper.harbor.season.ranking;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Archive schema name check and SQL prefix. Accepts only well-formed names since it is concatenated
 * into SQL.
 */
public final class SchemaName {

    private static final Pattern ARCHIVE = Pattern.compile("archive_s[0-9]{1,4}");

    private SchemaName() {}

    public static String archive(int season) {
        return "archive_s" + season;
    }

    /** A prefix such as {@code archive_s3.}. Empty string if null (current DB). */
    public static String prefix(@Nullable String schema) {
        if (schema == null) {
            return "";
        }
        if (!ARCHIVE.matcher(schema).matches()) {
            throw new IllegalArgumentException("아카이브 스키마 이름이 아닙니다: " + schema);
        }
        return "`" + schema + "`.";
    }
}
