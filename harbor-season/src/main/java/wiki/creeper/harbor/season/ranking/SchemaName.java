package wiki.creeper.harbor.season.ranking;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** 아카이브 스키마 이름 검사와 SQL 접두사. SQL에 이어 붙이므로 형식이 맞는 이름만 받는다. */
public final class SchemaName {

    private static final Pattern ARCHIVE = Pattern.compile("archive_s[0-9]{1,4}");

    private SchemaName() {}

    public static String archive(int season) {
        return "archive_s" + season;
    }

    /** {@code archive_s3.} 같은 접두사. null이면 빈 문자열 (지금 DB). */
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
