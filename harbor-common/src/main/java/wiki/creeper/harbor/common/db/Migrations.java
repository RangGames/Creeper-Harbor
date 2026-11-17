package wiki.creeper.harbor.common.db;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 모듈별 Flyway 마이그레이션. 마이그레이션 폴더는 {@code db/migration/<모듈>}, 이력 테이블은 {@code flyway_<모듈>}.
 *
 * <p>여러 모듈이 같은 스키마를 쓰므로 빈 스키마가 아니어도 시작할 수 있게 baseline 0으로 둔다. 운영 중에는 열을 지우거나 이름을 바꾸지 않고 추가만 한다.
 */
public final class Migrations {

    private static final Pattern MODULE = Pattern.compile("[a-z0-9_]+");

    private Migrations() {}

    /**
     * @param classLoader 마이그레이션 SQL이 들어 있는 jar의 클래스 로더 (플러그인 클래스 로더)
     * @return 이번에 적용한 마이그레이션 수
     */
    public static int migrate(DataSource dataSource, String module, ClassLoader classLoader) {
        if (!MODULE.matcher(module).matches()) {
            throw new IllegalArgumentException("모듈 이름 형식이 틀렸습니다: " + module);
        }
        MigrateResult result =
                Flyway.configure(classLoader)
                        .dataSource(dataSource)
                        .table("flyway_" + module)
                        .locations("classpath:db/migration/" + module)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate();
        return result.migrationsExecuted;
    }
}
