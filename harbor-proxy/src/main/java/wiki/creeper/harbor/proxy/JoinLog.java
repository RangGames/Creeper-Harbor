package wiki.creeper.harbor.proxy;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;

/**
 * Connection log (join_log, docs 20 1.3). The raw IP is not stored, only HMAC-SHA256(IP, secret
 * key). Same-IP checks still work, but a leak does not reveal the original IP. Rows older than 90
 * days (proposed value, retention_days) are deleted once a day.
 */
final class JoinLog {

    /** One /hp lookup row. */
    record Entry(
            String name,
            String ipHash,
            int protocol,
            @Nullable String brand,
            @Nullable String firstServer,
            Instant joinedAt,
            @Nullable Instant leftAt,
            @Nullable String kickReason) {}

    private final Database db;
    private final byte[] secret;
    private final Logger log;
    private final Map<UUID, Long> openRows = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingKick = new ConcurrentHashMap<>();

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

    /**
     * Inserts a row. Returns a future that completes when done (so multi-account detection can
     * follow on).
     */
    CompletableFuture<Void> joined(
            UUID uuid,
            String name,
            String hash,
            int protocol,
            @Nullable String brand,
            String firstServer) {
        return db.async(
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
                                            brand.length() > 64 ? brand.substring(0, 64) : brand);
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

    /** Remembers the reason when staff kick or sanction a player (recorded in DisconnectEvent). */
    void markKick(UUID uuid, String reason) {
        pendingKick.put(uuid, reason.length() > 255 ? reason.substring(0, 255) : reason);
    }

    void left(UUID uuid) {
        String kickReason = pendingKick.remove(uuid);
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
                                        if (kickReason == null) {
                                            ps.setNull(1, Types.VARCHAR);
                                        } else {
                                            ps.setString(1, kickReason);
                                        }
                                        ps.setLong(2, id);
                                        ps.executeUpdate();
                                    }
                                })
                        .exceptionally(e -> null);
    }

    /** Deletes rows past the retention period (once a day). */
    void purge(int retentionDays) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        var _ =
                db.async(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "DELETE FROM join_log WHERE joined_at < ?")) {
                                        ps.setTimestamp(1, Timestamp.from(cutoff));
                                        return ps.executeUpdate();
                                    }
                                })
                        .thenAccept(
                                rows -> {
                                    if (rows > 0) {
                                        log.info("join_log {}행 정리 ({}일 경과)", rows, retentionDays);
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn("join_log 정리 실패: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    // ------------------------------------------------------------------ Lookups (/hp lookup ·
    // alts)

    /**
     * Name → UUID. Checks players (accounts that joined the main server) first, otherwise the most
     * recent join in join_log.
     */
    CompletableFuture<Optional<UUID>> resolve(String name) {
        return db.async(
                c -> {
                    for (String sql :
                            List.of(
                                    "SELECT uuid FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1",
                                    "SELECT uuid FROM join_log WHERE name = ? ORDER BY joined_at DESC LIMIT 1")) {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setString(1, name);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    return Optional.of(UUID.fromString(rs.getString(1)));
                                }
                            }
                        }
                    }
                    return Optional.<UUID>empty();
                });
    }

    CompletableFuture<List<Entry>> recent(UUID uuid, int limit) {
        return db.async(
                c -> {
                    List<Entry> result = new ArrayList<>();
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT name, ip_hash, protocol, client_brand, first_server, joined_at, left_at, kick_reason"
                                            + " FROM join_log WHERE uuid = ? ORDER BY joined_at DESC LIMIT ?")) {
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                @Nullable Timestamp left = rs.getTimestamp("left_at");
                                result.add(
                                        new Entry(
                                                rs.getString("name"),
                                                rs.getString("ip_hash"),
                                                rs.getInt("protocol"),
                                                rs.getString("client_brand"),
                                                rs.getString("first_server"),
                                                rs.getTimestamp("joined_at").toInstant(),
                                                left == null ? null : left.toInstant(),
                                                rs.getString("kick_reason")));
                            }
                        }
                    }
                    return result;
                });
    }

    /** UUID → last seen name. Omitted if not in join_log. */
    CompletableFuture<Map<UUID, String>> names(Collection<UUID> uuids) {
        List<UUID> list = List.copyOf(uuids);
        return db.async(
                c -> {
                    Map<UUID, String> result = new HashMap<>();
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT name FROM join_log WHERE uuid = ? ORDER BY joined_at DESC LIMIT 1")) {
                        for (UUID uuid : list) {
                            ps.setString(1, uuid.toString());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    result.put(uuid, rs.getString(1));
                                }
                            }
                        }
                    }
                    return result;
                });
    }
}
