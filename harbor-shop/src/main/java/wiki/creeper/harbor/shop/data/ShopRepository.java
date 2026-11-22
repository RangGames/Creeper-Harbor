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

/** Shop table SQL. All calls block, so call them only on virtual threads (Database.async/tx). */
public final class ShopRepository {

    private ShopRepository() {}

    // ------------------------------------------------------------------ join load

    /** Pre-join load (PlayerComponent). */
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
        SubscriptionRepository.Subscription sub =
                SubscriptionRepository.subscription(c, player).orElse(null);
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
                ChestRepository.shards(c, player),
                creator);
    }

    // ------------------------------------------------------------------ wallet and lock

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

    /** Locks the wallet row and reads debt (inside a refund/payment transaction). */
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
     * Locks the buyer's wallet row at the very start of an order transaction. This serializes the
     * same player's orders so limit and ownership checks cannot pass concurrently, and the first
     * plain read must happen after the lock so the REPEATABLE READ snapshot sees the previous
     * order's commit.
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

    /** Lock reason. */
    public static Optional<String> lockReason(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT reason FROM shop_flags WHERE uuid = ? AND locked = 1")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Recent pearl ledger entries (/진주). */
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

    // ------------------------------------------------------------------ cosmetics

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
     * @return true if newly inserted (false if it already existed)
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

    // ------------------------------------------------------------------ orders

    /**
     * Records an order. Returns false if the same order_id already exists (double clicks, delayed
     * resends).
     */
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

    /** Total playtime (seconds). */
    public static long playtime(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT playtime FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** UUID by name (based on the last-join name). */
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

    /** Whether someone else uses the same display name or account name. */
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

    // ------------------------------------------------------------------ season pass

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

    /** Locks and reads the pass row (purchase/reward transaction). */
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

    /**
     * Saves exp and level (write-behind). Merged with GREATEST so a late-arriving old value never
     * overwrites a newer one.
     */
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

    /** Updates premium and purchased levels (immediate transaction). */
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
     * @return true if newly recorded (false if already claimed)
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

    /** A pass row (season close hook). */
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

    // ------------------------------------------------------------------ shared

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
