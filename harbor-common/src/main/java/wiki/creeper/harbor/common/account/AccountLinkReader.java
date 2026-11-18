package wiki.creeper.harbor.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 연결 계정 읽기 전용 조회 (docs 20 1.2, 추가 제안). alt_link·ip_shared는 Proxy 소유 테이블이고 Proxy의 AltDetector가 로그인마다
 * 채운다. Economy(같은 IP 거래 감시)와 Fishing(통발 부계정)이 Proxy에 의존하지 않고 이 클래스로 읽는다.
 *
 * <p>입출력이므로 메인 스레드에서 부르지 않는다 ({@code Database.async} 안에서 연결을 받아 쓴다). 자동 차단 판단은 하지 않는다: 공유
 * IP(PC방·학교)로 등록된 곳은 {@link LinkedAccount#sharedIp()}로 표시만 하고, {@link #linkedIds}와 {@link
 * #areLinked}는 공유 IP를 뺀다.
 */
public final class AccountLinkReader {

    /**
     * 연결 계정 한 줄.
     *
     * @param player 상대 계정
     * @param ipHash 같이 쓴 IP의 HMAC-SHA256 (원문 IP는 저장하지 않음)
     * @param hits 같은 IP로 함께 관측된 로그인 수
     * @param sharedIp ip_shared에 등록된 공유 IP인지
     */
    public record LinkedAccount(
            UUID player,
            String ipHash,
            Instant firstSeen,
            Instant lastSeen,
            int hits,
            boolean sharedIp) {}

    private static final String LINKED =
            "SELECT CASE WHEN l.uuid_a = ? THEN l.uuid_b ELSE l.uuid_a END AS other,"
                    + " l.ip_hash, l.first_seen, l.last_seen, l.hits,"
                    + " (s.ip_hash IS NOT NULL) AS shared"
                    + " FROM alt_link l LEFT JOIN ip_shared s ON s.ip_hash = l.ip_hash"
                    + " WHERE l.uuid_a = ? OR l.uuid_b = ?"
                    + " ORDER BY l.last_seen DESC";

    private static final String PAIR =
            "SELECT 1 FROM alt_link l LEFT JOIN ip_shared s ON s.ip_hash = l.ip_hash"
                    + " WHERE l.uuid_a = ? AND l.uuid_b = ? AND s.ip_hash IS NULL LIMIT 1";

    private AccountLinkReader() {}

    /** 연결 계정 전체 (공유 IP 포함, 최근 관측 순). 같은 계정이 IP 여러 개로 연결되면 줄도 여러 개다. */
    public static List<LinkedAccount> linked(Connection connection, UUID player)
            throws SQLException {
        List<LinkedAccount> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(LINKED)) {
            String id = player.toString();
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            new LinkedAccount(
                                    UUID.fromString(rs.getString("other")),
                                    rs.getString("ip_hash"),
                                    instant(rs.getTimestamp("first_seen")),
                                    instant(rs.getTimestamp("last_seen")),
                                    rs.getInt("hits"),
                                    rs.getBoolean("shared")));
                }
            }
        }
        return result;
    }

    /** 공유 IP를 뺀 연결 계정 UUID (거래 감시용). */
    public static Set<UUID> linkedIds(Connection connection, UUID player) throws SQLException {
        Set<UUID> result = new LinkedHashSet<>();
        for (LinkedAccount account : linked(connection, player)) {
            if (!account.sharedIp()) {
                result.add(account.player());
            }
        }
        return result;
    }

    /** 두 계정이 공유 IP가 아닌 IP로 연결돼 있는지. */
    public static boolean areLinked(Connection connection, UUID a, UUID b) throws SQLException {
        if (a.equals(b)) {
            return false;
        }
        String[] pair = orderedPair(a, b);
        try (PreparedStatement ps = connection.prepareStatement(PAIR)) {
            ps.setString(1, pair[0]);
            ps.setString(2, pair[1]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** alt_link의 저장 순서: UUID 문자열(소문자)이 작은 쪽이 uuid_a. 쓰는 쪽(Proxy)도 이 함수를 쓴다. */
    public static String[] orderedPair(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? new String[] {x, y} : new String[] {y, x};
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
