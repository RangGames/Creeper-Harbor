package wiki.creeper.harbor.common.db;

/**
 * MariaDB 연결 설정.
 *
 * @param poolSize 연결 풀 크기 (본 서버 20, 로비·인스턴스 8, 프록시 5로 시작)
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
        // 비밀번호는 로그에 남기지 않는다
        return "DatabaseConfig[" + jdbcUrl() + ", user=" + user + ", pool=" + poolSize + "]";
    }
}
