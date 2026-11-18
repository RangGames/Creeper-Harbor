package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.account.AccountLinkReader;
import wiki.creeper.harbor.common.db.Database;

/**
 * 다계정 탐지 (docs 20 1.4 AltDetector). 로그인마다 같은 ip_hash로 최근 30일 안에 들어온 다른 계정을 찾아 alt_link를 넣거나 hits를
 * 올린다. 판정 규칙은 {@link AltHeuristics}. 다른 모듈은 Common의 AccountLinkReader로 결과를 읽는다.
 */
final class AltDetector {

    private final ProxyServer proxy;
    private final Database db;
    private final ProxyConfig.Alt config;
    private final ProxyText text;
    private final Logger log;

    /** ip_hash → 마지막 경보 때 계정 수. 프록시 재시작이면 다시 한 번 경보가 갈 수 있다 (허용). */
    private final Map<String, Integer> alerted = new ConcurrentHashMap<>();

    AltDetector(
            ProxyServer proxy, Database db, ProxyConfig.Alt config, ProxyText text, Logger log) {
        this.proxy = proxy;
        this.db = db;
        this.config = config;
        this.text = text;
        this.log = log;
    }

    /** 결과: 그 IP의 계정 수와 공유 IP 여부. */
    record Observation(int accounts, boolean sharedIp) {}

    /** 로그인 직후 (join_log 행을 넣은 뒤) 비동기로 부른다. 실패해도 접속에는 영향이 없다. */
    CompletableFuture<Observation> observe(UUID uuid, String name, String ipHash) {
        Instant since = Instant.now().minus(Duration.ofDays(config.windowDays()));
        return db.async(c -> record(c, uuid, ipHash, since))
                .thenApply(
                        observation -> {
                            int last = alerted.getOrDefault(ipHash, 0);
                            if (AltHeuristics.shouldAlert(
                                    observation.accounts(),
                                    observation.sharedIp(),
                                    last,
                                    config.alertAccounts())) {
                                alerted.put(ipHash, observation.accounts());
                                log.warn(
                                        "다계정 의심: {} ({}) · 같은 IP 계정 {}개",
                                        name,
                                        uuid,
                                        observation.accounts());
                                text.notifyStaff(
                                        proxy,
                                        ProxyPerms.LOOKUP,
                                        text.get(
                                                "alt.alert",
                                                Placeholder.unparsed("name", name),
                                                Placeholder.unparsed(
                                                        "count",
                                                        String.valueOf(observation.accounts()))));
                            }
                            return observation;
                        })
                .exceptionally(
                        e -> {
                            log.warn("다계정 조회 실패 {}: {}", uuid, String.valueOf(e.getMessage()));
                            return new Observation(1, false);
                        });
    }

    private static Observation record(Connection c, UUID uuid, String ipHash, Instant since)
            throws SQLException {
        List<UUID> sameIp = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT DISTINCT uuid FROM join_log WHERE ip_hash = ? AND joined_at >= ?")) {
            ps.setString(1, ipHash);
            ps.setTimestamp(2, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sameIp.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        Set<UUID> partners = AltHeuristics.partners(uuid, sameIp);
        if (!partners.isEmpty()) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "INSERT INTO alt_link (uuid_a, uuid_b, ip_hash) VALUES (?, ?, ?)"
                                    + " ON DUPLICATE KEY UPDATE last_seen = CURRENT_TIMESTAMP(3), hits = hits + 1")) {
                for (UUID other : partners) {
                    String[] pair = AccountLinkReader.orderedPair(uuid, other);
                    ps.setString(1, pair[0]);
                    ps.setString(2, pair[1]);
                    ps.setString(3, ipHash);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        boolean shared;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT 1 FROM ip_shared WHERE ip_hash = ?")) {
            ps.setString(1, ipHash);
            try (ResultSet rs = ps.executeQuery()) {
                shared = rs.next();
            }
        }
        return new Observation(partners.size() + 1, shared);
    }

    // ------------------------------------------------------------------ 공유 IP (/hp sharedip)

    /** 플레이어의 가장 최근 ip_hash를 공유 IP로 등록한다. 등록한 해시 (기록이 없으면 빈 문자열). */
    CompletableFuture<String> addShared(UUID player, String label, String addedBy) {
        return db.async(
                c -> {
                    String hash = latestHash(c, player);
                    if (hash.isEmpty()) {
                        return hash;
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO ip_shared (ip_hash, label, added_by) VALUES (?, ?, ?)"
                                            + " ON DUPLICATE KEY UPDATE label = VALUES(label), added_by = VALUES(added_by)")) {
                        ps.setString(1, hash);
                        ps.setString(2, label.length() > 64 ? label.substring(0, 64) : label);
                        ps.setString(3, addedBy);
                        ps.executeUpdate();
                    }
                    alerted.remove(hash);
                    return hash;
                });
    }

    /** 플레이어의 가장 최근 ip_hash를 공유 IP에서 뺀다. 지운 행 수. */
    CompletableFuture<Integer> removeShared(UUID player) {
        return db.async(
                c -> {
                    String hash = latestHash(c, player);
                    if (hash.isEmpty()) {
                        return 0;
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement("DELETE FROM ip_shared WHERE ip_hash = ?")) {
                        ps.setString(1, hash);
                        return ps.executeUpdate();
                    }
                });
    }

    CompletableFuture<List<AccountLinkReader.LinkedAccount>> linked(UUID player) {
        return db.async(c -> AccountLinkReader.linked(c, player));
    }

    private static String latestHash(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT ip_hash FROM join_log WHERE uuid = ? ORDER BY joined_at DESC LIMIT 1")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }
}
