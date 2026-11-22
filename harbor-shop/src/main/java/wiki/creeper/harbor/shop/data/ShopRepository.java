package wiki.creeper.harbor.shop.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.donor.DonorGrace;

/** Shop 테이블 SQL. 모두 블로킹이므로 가상 스레드(Database.async/tx)에서만 부른다. */
public final class ShopRepository {

    private ShopRepository() {}

    // ------------------------------------------------------------------ 접속 로드

    /** 접속 전 로드 (PlayerComponent). */
    public static ShopProfile load(Connection c, UUID player, SeasonId season, Duration grace)
            throws SQLException {
        PearlService.Balance wallet = wallet(c, player);
        boolean locked = locked(c, player);
        Set<HarborId> owned = owned(c, player);
        Map<CosmeticDef.Category, HarborId> equipped = new EnumMap<>(CosmeticDef.Category.class);
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT slot, cosmetic_id FROM cosmetics_equipped WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<CosmeticDef.Category> slot =
                            Keyed.fromKey(CosmeticDef.Category.class, rs.getString(1));
                    Optional<HarborId> id = HarborId.tryParse(rs.getString(2));
                    if (slot.isPresent() && id.isPresent() && owned.contains(id.get())) {
                        equipped.put(slot.get(), id.get());
                    }
                }
            }
        }
        Subscription sub = subscription(c, player).orElse(null);
        DonorTier tier =
                sub == null
                        ? DonorTier.NONE
                        : DonorGrace.effective(
                                sub.state(), sub.tier(), sub.periodEnd(), Instant.now(), grace);
        ShopProfile.Pass pass = pass(c, player, season.value());
        String displayName = null;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT display_name FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    displayName = rs.getString(1);
                }
            }
        }
        String creator = null;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT code FROM creator_support WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    creator = rs.getString(1);
                }
            }
        }
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                tier,
                sub == null ? DonorGrace.State.ENDED : sub.state(),
                sub == null ? null : sub.periodEnd(),
                pass,
                displayName,
                shards(c, player),
                creator);
    }

    // ------------------------------------------------------------------ 지갑·정지

    public static PearlService.Balance wallet(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT balance, paid_balance, debt FROM pearl_wallet WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new PearlService.Balance(rs.getLong(1), rs.getLong(2), rs.getLong(3))
                        : PearlService.Balance.EMPTY;
            }
        }
    }

    /** 지갑 행을 잠그고 debt를 읽는다 (환불·결제 처리 트랜잭션 안). */
    public static long lockDebt(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("INSERT IGNORE INTO pearl_wallet (uuid) VALUES (?)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement("SELECT debt FROM pearl_wallet WHERE uuid = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * 주문 트랜잭션 맨 앞에서 산 사람 지갑 행을 잠근다. 같은 플레이어의 주문을 줄 세워 한도·소유 검사가 동시에 통과하지 않게 하고, 잠금 뒤에 첫 일반 읽기가 일어나야
     * REPEATABLE READ 스냅샷이 앞 주문의 커밋을 본다.
     */
    public static void lockWallet(Connection c, UUID player) throws SQLException {
        lockDebt(c, player);
    }

    public static void addDebt(Connection c, UUID player, long delta) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE pearl_wallet SET debt = GREATEST(0, debt + ?) WHERE uuid = ?")) {
            ps.setLong(1, delta);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    public static boolean locked(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT locked FROM shop_flags WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    public static void setLock(Connection c, UUID player, boolean locked, @Nullable String reason)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO shop_flags (uuid, locked, reason) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE locked = VALUES(locked), reason = VALUES(reason)")) {
            ps.setString(1, player.toString());
            ps.setBoolean(2, locked);
            setNullable(ps, 3, reason);
            ps.executeUpdate();
        }
    }

    /** 정지 사유. */
    public static Optional<String> lockReason(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT reason FROM shop_flags WHERE uuid = ? AND locked = 1")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** 최근 진주 원장 (/진주). */
    public record LedgerLine(long delta, String reason, @Nullable String refId, Instant at) {}

    public static List<LedgerLine> recentLedger(Connection c, UUID player, int limit)
            throws SQLException {
        List<LedgerLine> lines = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT delta, reason, ref_id, created_at FROM pearl_ledger WHERE uuid = ?"
                                + " ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(
                            new LedgerLine(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getTimestamp(4).toInstant()));
                }
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ 코스메틱

    public static Set<HarborId> owned(Connection c, UUID player) throws SQLException {
        Set<HarborId> owned = new HashSet<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT cosmetic_id FROM cosmetics_owned WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HarborId.tryParse(rs.getString(1)).ifPresent(owned::add);
                }
            }
        }
        return owned;
    }

    public static boolean ownsRow(Connection c, UUID player, HarborId cosmetic)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM cosmetics_owned WHERE uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * @return 새로 넣었으면 true (이미 있으면 false)
     */
    public static boolean insertOwned(
            Connection c, UUID player, HarborId cosmetic, String source, @Nullable String refId)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO cosmetics_owned (uuid, cosmetic_id, source, ref_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic.toString());
            ps.setString(3, source);
            setNullable(ps, 4, refId);
            return ps.executeUpdate() == 1;
        }
    }

    public static boolean deleteOwned(Connection c, UUID player, HarborId cosmetic)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM cosmetics_equipped WHERE uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM cosmetics_owned WHERE uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public static void setEquipped(
            Connection c, UUID player, CosmeticDef.Category slot, @Nullable HarborId cosmetic)
            throws SQLException {
        if (cosmetic == null) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "DELETE FROM cosmetics_equipped WHERE uuid = ? AND slot = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, slot.key());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO cosmetics_equipped (uuid, slot, cosmetic_id) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE cosmetic_id = VALUES(cosmetic_id)")) {
            ps.setString(1, player.toString());
            ps.setString(2, slot.key());
            ps.setString(3, cosmetic.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 주문

    /** 주문 기록. 같은 order_id가 이미 있으면 false (연타·지연 재전송). */
    public static boolean insertOrder(
            Connection c,
            UUID orderId,
            UUID player,
            String kind,
            String target,
            long pearls,
            @Nullable UUID recipient)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO shop_order (order_id, uuid, kind, target, pearls, recipient)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, orderId.toString());
            ps.setString(2, player.toString());
            ps.setString(3, kind);
            ps.setString(4, target);
            ps.setLong(5, pearls);
            setNullable(ps, 6, recipient == null ? null : recipient.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public static int countOrders(Connection c, UUID player, String kind, String target)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM shop_order WHERE uuid = ? AND kind = ? AND target = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setString(3, target);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static int countOrdersSince(Connection c, UUID player, String kind, Instant since)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM shop_order WHERE uuid = ? AND kind = ? AND created_at >= ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setTimestamp(3, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ players (Common)

    /** 누적 플레이 시간 (초). */
    public static long playtime(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT playtime FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 이름으로 UUID (마지막 접속 이름 기준). */
    public static Optional<UUID> uuidByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /** 다른 사람이 같은 표시 이름이나 계정 이름을 쓰는지. */
    public static boolean nameTaken(Connection c, UUID player, String name) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM players WHERE uuid <> ? AND (display_name = ? OR name = ?) LIMIT 1")) {
            ps.setString(1, player.toString());
            ps.setString(2, name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void setDisplayName(Connection c, UUID player, @Nullable String name)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE players SET display_name = ? WHERE uuid = ?")) {
            setNullable(ps, 1, name);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 시즌 패스

    public static ShopProfile.Pass pass(Connection c, UUID player, int season) throws SQLException {
        long exp = 0;
        int bought = 0;
        int premium = 0;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT exp, bought, premium FROM season_pass WHERE uuid = ? AND season = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exp = rs.getLong(1);
                    bought = rs.getInt(2);
                    premium = rs.getInt(3);
                }
            }
        }
        return new ShopProfile.Pass(season, exp, bought, premium, claimed(c, player, season));
    }

    /** 패스 행을 잠그고 읽는다 (구매·보상 트랜잭션). */
    public static ShopProfile.Pass lockPass(Connection c, UUID player, int season)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("INSERT IGNORE INTO season_pass (uuid, season) VALUES (?, ?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT exp, bought, premium FROM season_pass WHERE uuid = ? AND season = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new ShopProfile.Pass(
                        season,
                        rs.getLong(1),
                        rs.getInt(2),
                        rs.getInt(3),
                        claimed(c, player, season));
            }
        }
    }

    /** 경험치·단계 저장 (지연 쓰기). 늦게 도착한 옛 값이 새 값을 덮지 않게 GREATEST로 합친다. */
    public static void savePassExp(Connection c, UUID player, int season, long exp, int level)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO season_pass (uuid, season, exp, level) VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE exp = GREATEST(exp, VALUES(exp)),"
                                + " level = GREATEST(level, VALUES(level))")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setLong(3, exp);
            ps.setInt(4, level);
            ps.executeUpdate();
        }
    }

    /** 프리미엄·구매 단계 갱신 (즉시 트랜잭션). */
    public static void updatePassPurchase(
            Connection c, UUID player, int season, int premium, int bought, int level)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE season_pass SET premium = ?, bought = ?, level = GREATEST(level, ?)"
                                + " WHERE uuid = ? AND season = ?")) {
            ps.setInt(1, premium);
            ps.setInt(2, bought);
            ps.setInt(3, level);
            ps.setString(4, player.toString());
            ps.setInt(5, season);
            ps.executeUpdate();
        }
    }

    public static Set<String> claimed(Connection c, UUID player, int season) throws SQLException {
        Set<String> claimed = new HashSet<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT level, track FROM season_pass_claim WHERE uuid = ? AND season = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getInt(1) + ":" + rs.getString(2));
                }
            }
        }
        return claimed;
    }

    /**
     * @return 새로 기록했으면 true (이미 받았으면 false)
     */
    public static boolean insertClaim(
            Connection c, UUID player, int season, int level, String track) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO season_pass_claim (uuid, season, level, track) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setInt(3, level);
            ps.setString(4, track);
            return ps.executeUpdate() == 1;
        }
    }

    /** 패스 행 (시즌 마감 훅). */
    public record PassRow(UUID player, long exp, int bought, int premium) {}

    public static List<PassRow> passRows(Connection c, int season) throws SQLException {
        List<PassRow> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, exp, bought, premium FROM season_pass WHERE season = ?")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            new PassRow(
                                    UUID.fromString(rs.getString(1)),
                                    rs.getLong(2),
                                    rs.getInt(3),
                                    rs.getInt(4)));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ 보물함

    public static int lockPity(Connection c, UUID player, int season, HarborId chest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO chest_pity (uuid, season, chest_id) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT count FROM chest_pity WHERE uuid = ? AND season = ? AND chest_id = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static int pity(Connection c, UUID player, int season, HarborId chest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT count FROM chest_pity WHERE uuid = ? AND season = ? AND chest_id = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static void setPity(Connection c, UUID player, int season, HarborId chest, int count)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE chest_pity SET count = ? WHERE uuid = ? AND season = ? AND chest_id = ?")) {
            ps.setInt(1, count);
            ps.setString(2, player.toString());
            ps.setInt(3, season);
            ps.setString(4, chest.toString());
            ps.executeUpdate();
        }
    }

    /** 개봉 기록 한 줄. */
    public record OpenLog(
            UUID player,
            HarborId chest,
            int tableVersion,
            int roll,
            HarborId result,
            String grade,
            boolean pityHit,
            boolean duplicate,
            int pityBefore,
            UUID orderId) {}

    public static void logOpen(Connection c, OpenLog log) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO chest_open_log (uuid, chest_id, table_version, roll, result, grade,"
                                + " pity_hit, duplicate, pity_before, order_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, log.player().toString());
            ps.setString(2, log.chest().toString());
            ps.setInt(3, log.tableVersion());
            ps.setInt(4, log.roll());
            ps.setString(5, log.result().toString());
            ps.setString(6, log.grade());
            ps.setBoolean(7, log.pityHit());
            ps.setBoolean(8, log.duplicate());
            ps.setInt(9, log.pityBefore());
            ps.setString(10, log.orderId().toString());
            ps.executeUpdate();
        }
    }

    public static long shards(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT shards FROM shop_shards WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 진주 조각을 더하거나 뺀다. 음수가 되면 false (변경 없음). */
    public static boolean addShards(Connection c, UUID player, long delta) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("INSERT IGNORE INTO shop_shards (uuid) VALUES (?)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE shop_shards SET shards = shards + ? WHERE uuid = ? AND shards + ? >= 0")) {
            ps.setLong(1, delta);
            ps.setString(2, player.toString());
            ps.setLong(3, delta);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ 구독

    /** subscriptions 한 행. */
    public record Subscription(
            UUID player,
            DonorTier tier,
            @Nullable String recurringId,
            DonorGrace.State state,
            @Nullable Instant periodEnd) {}

    public static Optional<Subscription> subscription(Connection c, UUID player)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(subscriptionRow(rs)) : Optional.empty();
            }
        }
    }

    public static Optional<Subscription> subscriptionByRecurring(Connection c, String recurringId)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions"
                                + " WHERE tebex_recurring_id = ? FOR UPDATE")) {
            ps.setString(1, recurringId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(subscriptionRow(rs)) : Optional.empty();
            }
        }
    }

    /** 끝나지 않은 구독 전체 (시작 대조·유예 정리). */
    public static List<Subscription> openSubscriptions(Connection c) throws SQLException {
        List<Subscription> rows = new ArrayList<>();
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions"
                                        + " WHERE state <> 'ENDED'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(subscriptionRow(rs));
            }
        }
        return rows;
    }

    public static void upsertSubscription(Connection c, Subscription s) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO subscriptions (uuid, tier, tebex_recurring_id, state, period_end)"
                                + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE tier = VALUES(tier),"
                                + " tebex_recurring_id = COALESCE(VALUES(tebex_recurring_id), tebex_recurring_id),"
                                + " state = VALUES(state), period_end = VALUES(period_end)")) {
            ps.setString(1, s.player().toString());
            ps.setString(2, s.tier().name());
            setNullable(ps, 3, s.recurringId());
            ps.setString(4, s.state().name());
            if (s.periodEnd() == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, Timestamp.from(s.periodEnd()));
            }
            ps.executeUpdate();
        }
    }

    /** 유예가 지난 구독을 ENDED로 (기대 상태 조건부). 바뀌었으면 true. */
    public static boolean endSubscription(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE subscriptions SET state = 'ENDED' WHERE uuid = ? AND state <> 'ENDED'")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() == 1;
        }
    }

    private static Subscription subscriptionRow(ResultSet rs) throws SQLException {
        Timestamp end = rs.getTimestamp(5);
        return new Subscription(
                UUID.fromString(rs.getString(1)),
                DonorTier.parse(rs.getString(2)),
                rs.getString(3),
                DonorGrace.State.parse(rs.getString(4)),
                end == null ? null : end.toInstant());
    }

    // ------------------------------------------------------------------ 알림

    /** 미확인 알림 한 건. */
    public record Notice(long id, String kind, long amount, @Nullable String detail) {}

    public static void insertNotice(
            Connection c, UUID player, String kind, long amount, @Nullable String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO shop_notice (uuid, kind, amount, detail) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setLong(3, amount);
            setNullable(ps, 4, detail);
            ps.executeUpdate();
        }
    }

    /** 미확인 알림을 읽고 확인 처리한다 (한 트랜잭션). */
    public static List<Notice> takeNotices(Connection c, UUID player) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT id, kind, amount, detail FROM shop_notice WHERE uuid = ? AND seen_at IS NULL"
                                + " ORDER BY id LIMIT 20 FOR UPDATE")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notices.add(
                            new Notice(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getString(4)));
                }
            }
        }
        for (Notice n : notices) {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE shop_notice SET seen_at = NOW(3) WHERE id = ?")) {
                ps.setLong(1, n.id());
                ps.executeUpdate();
            }
        }
        return notices;
    }

    // ------------------------------------------------------------------ 공용

    public static void setNullable(PreparedStatement ps, int index, @Nullable String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    public static void adminLog(
            Connection c,
            String admin,
            String action,
            @Nullable String target,
            @Nullable String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setString(2, "shop." + action);
            setNullable(ps, 3, target);
            setNullable(ps, 4, detail);
            ps.executeUpdate();
        }
    }
}
