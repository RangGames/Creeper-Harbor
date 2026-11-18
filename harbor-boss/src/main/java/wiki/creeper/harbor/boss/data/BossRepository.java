package wiki.creeper.harbor.boss.data;

import com.google.gson.JsonArray;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRecord;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.SeasonId;

/** Boss SQL (docs 20 10.3). 트랜잭션은 호출하는 쪽이 연다. 모든 메서드는 블로킹이라 가상 스레드에서 부른다. */
public final class BossRepository {

    /** 기록을 남기는 시즌별 순위 수 (1~10위). */
    public static final int RECORDS_KEPT = 10;

    /** 시작할 때 쓰는 조우 행. */
    public record EncounterRow(
            UUID id,
            BossId boss,
            int season,
            int week,
            String server,
            int slot,
            int partySize,
            Instant startedAt,
            @Nullable UUID leader,
            @Nullable String summonItem,
            long seed) {}

    /** 재시작 때 ABORTED로 닫은 조우 (미끼 환급 대상). */
    public record Aborted(UUID id, @Nullable UUID leader, @Nullable String summonItem) {}

    private BossRepository() {}

    // ------------------------------------------------------------------ boss_encounter

    public static void insertEncounter(Connection c, EncounterRow row) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO boss_encounter (encounter_id, boss_id, season, week, server, arena_slot,"
                                + " party_size, state, started_at, leader, summon_item, seed)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)")) {
            ps.setString(1, row.id().toString());
            ps.setString(2, row.boss().toString());
            ps.setInt(3, row.season());
            ps.setInt(4, row.week());
            ps.setString(5, row.server());
            ps.setInt(6, row.slot());
            ps.setInt(7, row.partySize());
            ps.setTimestamp(8, Timestamp.from(row.startedAt()));
            UUID leader = row.leader();
            if (leader == null) {
                ps.setNull(9, Types.VARCHAR);
            } else {
                ps.setString(9, leader.toString());
            }
            ps.setString(10, row.summonItem());
            ps.setLong(11, row.seed());
            ps.executeUpdate();
        }
    }

    /** RUNNING 행만 닫는다 (이미 닫혔으면 false). */
    public static boolean endEncounter(
            Connection c, UUID id, String state, Instant endedAt, long durationMs, int partySize)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE boss_encounter SET state = ?, ended_at = ?, duration_ms = ?, party_size = ?"
                                + " WHERE encounter_id = ? AND state = 'RUNNING'")) {
            ps.setString(1, state);
            ps.setTimestamp(2, Timestamp.from(endedAt));
            ps.setLong(3, durationMs);
            ps.setInt(4, partySize);
            ps.setString(5, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public static void updateProgress(Connection c, UUID id, double progress, int partySize)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE boss_encounter SET progress = ?, party_size = ? WHERE encounter_id = ?")) {
            ps.setDouble(1, progress);
            ps.setInt(2, partySize);
            ps.setString(3, id.toString());
            ps.executeUpdate();
        }
    }

    /** 이 서버에 남은 RUNNING 조우를 ABORTED로 닫는다 (서버 비정상 종료 뒤 재시작, 10.4). 월드 보스 행은 resume을 위해 남긴다. */
    public static List<Aborted> abortRunning(Connection c, String server) throws SQLException {
        List<Aborted> out = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT encounter_id, leader, summon_item FROM boss_encounter"
                                + " WHERE server = ? AND state = 'RUNNING' AND arena_slot >= 0 FOR UPDATE")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String leader = rs.getString(2);
                    out.add(
                            new Aborted(
                                    UUID.fromString(rs.getString(1)),
                                    leader == null ? null : UUID.fromString(leader),
                                    rs.getString(3)));
                }
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE boss_encounter SET state = 'ABORTED', ended_at = CURRENT_TIMESTAMP(3)"
                                + " WHERE encounter_id = ? AND state = 'RUNNING'")) {
            for (Aborted a : out) {
                ps.setString(1, a.id().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return out;
    }

    /** 월드 보스 RUNNING 행 (resume 대상, 가장 최근 것). */
    public static @Nullable UUID runningWorldBoss(Connection c, String bossId) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT encounter_id FROM boss_encounter WHERE boss_id = ? AND state = 'RUNNING'"
                                + " AND arena_slot = -1 ORDER BY started_at DESC LIMIT 1")) {
            ps.setString(1, bossId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    // ------------------------------------------------------------------ boss_participation

    public static void upsertParticipation(
            Connection c, UUID encounter, int season, List<Contribution> rows) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO boss_participation (encounter_id, uuid, season, role, damage, tension,"
                                + " repair, evasion, score, `rank`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE role = VALUES(role), damage = VALUES(damage),"
                                + " tension = VALUES(tension), repair = VALUES(repair), evasion = VALUES(evasion),"
                                + " score = VALUES(score), `rank` = VALUES(`rank`)")) {
            for (Contribution r : rows) {
                ps.setString(1, encounter.toString());
                ps.setString(2, r.player().toString());
                ps.setInt(3, season);
                ps.setString(4, r.role().key());
                ps.setDouble(5, r.damage());
                ps.setDouble(6, r.tension());
                ps.setDouble(7, r.repair());
                ps.setDouble(8, r.evasion());
                ps.setDouble(9, r.score());
                ps.setInt(10, r.rank());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 보상 표시 (0 → 1). 이미 1이면 false — 보상 트랜잭션에서 이것부터 해서 같은 보상이 두 번 들어가지 않게 한다. */
    public static boolean markRewarded(Connection c, UUID encounter, UUID player)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE boss_participation SET rewarded = 1"
                                + " WHERE encounter_id = ? AND uuid = ? AND rewarded = 0")) {
            ps.setString(1, encounter.toString());
            ps.setString(2, player.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** 월드 보스 resume: 저장된 기여도. */
    public static List<Contribution> participation(Connection c, UUID encounter)
            throws SQLException {
        List<Contribution> out = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, role, damage, tension, repair, evasion, score, `rank`"
                                + " FROM boss_participation WHERE encounter_id = ?")) {
            ps.setString(1, encounter.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BossRole role =
                            Keyed.fromKey(BossRole.class, rs.getString(2))
                                    .orElse(BossRole.HARPOONER);
                    out.add(
                            new Contribution(
                                    UUID.fromString(rs.getString(1)),
                                    role,
                                    rs.getDouble(3),
                                    rs.getDouble(4),
                                    rs.getDouble(5),
                                    rs.getDouble(6),
                                    rs.getDouble(7),
                                    rs.getInt(8),
                                    false,
                                    true));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ boss_records

    /** members JSON (UUID 문자열 배열, 파티장 먼저). */
    public static String membersJson(List<UUID> members) {
        JsonArray array = new JsonArray();
        members.forEach(m -> array.add(m.toString()));
        return array.toString();
    }

    /**
     * 기록을 넣고 그 시즌 10위 밖을 지운다.
     *
     * @return 넣은 기록의 순위 (10위 밖이면 0)
     */
    public static int insertRecord(Connection c, BossRecord record) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO boss_records (encounter_id, boss_id, season, duration_ms, members,"
                                + " cleared_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, record.encounterId().toString());
            ps.setString(2, record.boss().toString());
            ps.setInt(3, record.season());
            ps.setLong(4, record.durationMs());
            ps.setString(5, membersJson(record.members()));
            ps.setTimestamp(6, Timestamp.from(record.clearedAt()));
            ps.executeUpdate();
        }
        List<UUID> order = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT encounter_id FROM boss_records WHERE boss_id = ? AND season = ?"
                                + " ORDER BY duration_ms ASC, cleared_at ASC FOR UPDATE")) {
            ps.setString(1, record.boss().toString());
            ps.setInt(2, record.season());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    order.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        int rank = order.indexOf(record.encounterId()) + 1;
        if (order.size() > RECORDS_KEPT) {
            try (PreparedStatement ps =
                    c.prepareStatement("DELETE FROM boss_records WHERE encounter_id = ?")) {
                for (UUID drop : order.subList(RECORDS_KEPT, order.size())) {
                    ps.setString(1, drop.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return rank > RECORDS_KEPT ? 0 : rank;
    }

    public static List<BossRecord> records(
            Connection c, BossId boss, @Nullable Integer season, int limit) throws SQLException {
        String sql =
                "SELECT encounter_id, season, duration_ms, members, cleared_at FROM boss_records"
                        + " WHERE boss_id = ?"
                        + (season == null ? "" : " AND season = ?")
                        + " ORDER BY duration_ms ASC, cleared_at ASC LIMIT ?";
        List<BossRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, boss.toString());
            if (season != null) {
                ps.setInt(i++, season);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new BossRecord(
                                    boss,
                                    rs.getInt(2),
                                    rs.getLong(3),
                                    UUID.fromString(rs.getString(1)),
                                    parseMembers(rs.getString(4)),
                                    rs.getTimestamp(5).toInstant()));
                }
            }
        }
        return out;
    }

    static List<UUID> parseMembers(@Nullable String json) {
        List<UUID> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        try {
            for (var e : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
                out.add(UUID.fromString(e.getAsString()));
            }
        } catch (RuntimeException e) {
            // 형식이 틀린 행은 빈 목록
        }
        return out;
    }

    // ------------------------------------------------------------------ boss_reward_pending

    public static void insertPending(Connection c, PendingReward reward) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO boss_reward_pending (id, encounter_id, uuid, payload) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, reward.id().toString());
            ps.setString(2, reward.encounter().toString());
            ps.setString(3, reward.player().toString());
            ps.setString(4, reward.payloadJson());
            ps.executeUpdate();
        }
    }

    /** 이미 있으면 넣지 않는다 (미끼 환급처럼 재시작마다 다시 시도하는 보상). */
    public static boolean insertPendingIgnore(Connection c, PendingReward reward)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO boss_reward_pending (id, encounter_id, uuid, payload) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, reward.id().toString());
            ps.setString(2, reward.encounter().toString());
            ps.setString(3, reward.player().toString());
            ps.setString(4, reward.payloadJson());
            return ps.executeUpdate() == 1;
        }
    }

    public static List<PendingReward> undelivered(Connection c, UUID player) throws SQLException {
        List<PendingReward> out = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT id, encounter_id, payload FROM boss_reward_pending"
                                + " WHERE uuid = ? AND delivered_at IS NULL ORDER BY created_at")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            PendingReward.fromJson(
                                    UUID.fromString(rs.getString(1)),
                                    UUID.fromString(rs.getString(2)),
                                    player,
                                    rs.getString(3)));
                }
            }
        }
        return out;
    }

    public static void markDelivered(Connection c, List<UUID> ids) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE boss_reward_pending SET delivered_at = CURRENT_TIMESTAMP(3)"
                                + " WHERE id = ? AND delivered_at IS NULL")) {
            for (UUID id : ids) {
                ps.setString(1, id.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ boss_pity

    public static Map<String, Integer> pity(Connection c, UUID player, int season, BossId boss)
            throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT drop_id, count FROM boss_pity WHERE uuid = ? AND season = ? AND boss_id = ?"
                                + " FOR UPDATE")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, boss.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return out;
    }

    public static void savePity(
            Connection c, UUID player, int season, BossId boss, Map<String, Integer> counts)
            throws SQLException {
        if (counts.isEmpty()) {
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO boss_pity (uuid, season, boss_id, drop_id, count) VALUES (?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE count = VALUES(count)")) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                ps.setString(1, player.toString());
                ps.setInt(2, season);
                ps.setString(3, boss.toString());
                ps.setString(4, e.getKey());
                ps.setInt(5, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ boss_profile

    public static BossProfile loadProfile(Connection c, UUID player, SeasonId season)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT preferred_role, return_server, return_world, return_x, return_y, return_z,"
                                + " return_yaw, return_pitch FROM boss_profile WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BossProfile.EMPTY;
                }
                String roleKey = rs.getString(1);
                BossRole role =
                        roleKey == null
                                ? null
                                : Keyed.fromKey(BossRole.class, roleKey).orElse(null);
                String server = rs.getString(2);
                String world = rs.getString(3);
                BossProfile.ReturnPoint point =
                        server == null || world == null
                                ? null
                                : new BossProfile.ReturnPoint(
                                        server,
                                        world,
                                        rs.getDouble(4),
                                        rs.getDouble(5),
                                        rs.getDouble(6),
                                        rs.getFloat(7),
                                        rs.getFloat(8));
                return new BossProfile(role, point);
            }
        }
    }

    public static void saveProfile(Connection c, UUID player, SeasonId season, BossProfile profile)
            throws SQLException {
        BossRole role = profile.preferred();
        BossProfile.ReturnPoint p = profile.returnPoint();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO boss_profile (uuid, preferred_role, return_server, return_world,"
                                + " return_x, return_y, return_z, return_yaw, return_pitch)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                                + " preferred_role = VALUES(preferred_role), return_server = VALUES(return_server),"
                                + " return_world = VALUES(return_world), return_x = VALUES(return_x),"
                                + " return_y = VALUES(return_y), return_z = VALUES(return_z),"
                                + " return_yaw = VALUES(return_yaw), return_pitch = VALUES(return_pitch)")) {
            ps.setString(1, player.toString());
            ps.setString(2, role == null ? null : role.key());
            if (p == null) {
                for (int i = 3; i <= 9; i++) {
                    ps.setNull(i, i <= 4 ? Types.VARCHAR : Types.DOUBLE);
                }
            } else {
                ps.setString(3, p.server());
                ps.setString(4, p.world());
                ps.setDouble(5, p.x());
                ps.setDouble(6, p.y());
                ps.setDouble(7, p.z());
                ps.setFloat(8, p.yaw());
                ps.setFloat(9, p.pitch());
            }
            ps.executeUpdate();
        }
    }

    /** 문자열 ID → 보스 ID (형식이 틀리면 null). */
    public static @Nullable BossId bossId(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BossId(HarborId.parse(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
