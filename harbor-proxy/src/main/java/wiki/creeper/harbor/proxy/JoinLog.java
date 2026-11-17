package wiki.creeper.harbor.proxy;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;

/**
 * 접속 기록 (join_log, docs 20 1.3). 원문 IP는 저장하지 않고 HMAC-SHA256(IP, 비밀 키)만 저장한다. 같은 IP 판정은 되지만 유출돼도 원래
 * IP를 알 수 없다.
 */
final class JoinLog {

    private final Database db;
    private final byte[] secret;
    private final Logger log;
    private final Map<UUID, Long> openRows = new ConcurrentHashMap<>();

    JoinLog(Database db, String secret, Logger log) {
        this.db = db;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.log = log;
    }

    String ipHash(InetAddress address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(address.getAddress()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256을 쓸 수 없습니다", e);
        }
    }

    void joined(
            UUID uuid,
            String name,
            InetAddress address,
            int protocol,
            @Nullable String brand,
            String firstServer) {
        String hash = ipHash(address);
        var _ =
                db.async(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO join_log (uuid, name, ip_hash, protocol, client_brand, first_server) VALUES (?, ?, ?, ?, ?, ?)",
                                                    Statement.RETURN_GENERATED_KEYS)) {
                                        ps.setString(1, uuid.toString());
                                        ps.setString(2, name);
                                        ps.setString(3, hash);
                                        ps.setInt(4, protocol);
                                        if (brand == null) {
                                            ps.setNull(5, Types.VARCHAR);
                                        } else {
                                            ps.setString(
                                                    5,
                                                    brand.length() > 64
                                                            ? brand.substring(0, 64)
                                                            : brand);
                                        }
                                        ps.setString(6, firstServer);
                                        ps.executeUpdate();
                                        try (ResultSet keys = ps.getGeneratedKeys()) {
                                            return keys.next() ? keys.getLong(1) : -1L;
                                        }
                                    }
                                })
                        .thenAccept(
                                id -> {
                                    if (id > 0) {
                                        openRows.put(uuid, id);
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn("join_log 기록 실패: {}", uuid);
                                    return null;
                                });
    }

    void left(UUID uuid, @Nullable String kickReason) {
        Long id = openRows.remove(uuid);
        if (id == null) {
            return;
        }
        var _ =
                db.asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "UPDATE join_log SET left_at = CURRENT_TIMESTAMP(3), kick_reason = ? WHERE id = ?")) {
                                        ps.setString(1, kickReason);
                                        ps.setLong(2, id);
                                        ps.executeUpdate();
                                    }
                                })
                        .exceptionally(e -> null);
    }
}
