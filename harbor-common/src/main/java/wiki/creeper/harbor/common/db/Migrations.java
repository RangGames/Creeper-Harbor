package wiki.creeper.harbor.common.db;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Per-module Flyway migrations. Migration folder is {@code db/migration/<module>}, history table is
 * {@code flyway_<module>}.
 *
 * <p>Several modules share one schema, so baseline is 0 to allow starting on a non-empty schema. In
 * production, columns are only added, never dropped or renamed.
 */
public final class Migrations {

    private static final Pattern MODULE = Pattern.compile("[a-z0-9_]+");

    private Migrations() {}

    /**
     * @param classLoader class loader of the jar containing the migration SQL (the plugin class
     *     loader)
     * @return number of migrations applied this time
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
