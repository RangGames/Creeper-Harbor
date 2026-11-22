package wiki.creeper.harbor.common.db;

/**
 * MariaDB connection settings.
 *
 * @param poolSize connection pool size (start with 20 on the main server, 8 on lobby/instances, 5
 *     on the proxy)
 */
public record DatabaseConfig(
        String host,
        int port,
        String name,
        String user,
        String password,
        int poolSize,
        String poolName) {

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + name;
    }

    @Override
    public String toString() {
        // Never log the password
        return "DatabaseConfig[" + jdbcUrl() + ", user=" + user + ", pool=" + poolSize + "]";
    }
}
