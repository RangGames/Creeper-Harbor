package wiki.creeper.harbor.world.ship;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.UpgradePath;

/** ships table (virtual threads). */
public final class ShipRepository {

    private ShipRepository() {}

    /** One ships row (immutable). cargo is the result of ItemStack.serializeItemsAsBytes. */
    public static final class Row {

        private final long shipId;
        private final UUID owner;
        private final HarborId type;
        private final Map<UpgradePath, Integer> upgrades;
        private final int durability;
        private final ShipState state;
        private final byte @Nullable [] cargo;

        public Row(
                long shipId,
                UUID owner,
                HarborId type,
                Map<UpgradePath, Integer> upgrades,
                int durability,
                ShipState state,
                byte @Nullable [] cargo) {
            this.shipId = shipId;
            this.owner = owner;
            this.type = type;
            this.upgrades = Map.copyOf(upgrades);
            this.durability = durability;
            this.state = state;
            this.cargo = cargo == null ? null : cargo.clone();
        }

        public long shipId() {
            return shipId;
        }

        public UUID owner() {
            return owner;
        }

        public HarborId type() {
            return type;
        }

        public Map<UpgradePath, Integer> upgrades() {
            return upgrades;
        }

        public int durability() {
            return durability;
        }

        public ShipState state() {
            return state;
        }

        public byte @Nullable [] cargo() {
            return cargo == null ? null : cargo.clone();
        }

        public Row withDurability(int value, ShipState newState) {
            return new Row(shipId, owner, type, upgrades, value, newState, cargo);
        }

        public Row withCargo(byte @Nullable [] bytes) {
            return new Row(shipId, owner, type, upgrades, durability, state, bytes);
        }

        public Row withUpgrades(Map<UpgradePath, Integer> next, int newDurability) {
            return new Row(shipId, owner, type, next, newDurability, state, cargo);
        }
    }

    public static List<Row> load(Connection c, UUID owner, int season) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT ship_id, type, upgrades, durability, state, cargo FROM ships "
                                + "WHERE owner = ? AND season = ? ORDER BY ship_id")) {
            ps.setObject(1, owner);
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HarborId type;
                    try {
                        type = HarborId.parse(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    ShipState state;
                    try {
                        state = ShipState.valueOf(rs.getString(5));
                    } catch (IllegalArgumentException e) {
                        state = ShipState.STORED;
                    }
                    rows.add(
                            new Row(
                                    rs.getLong(1),
                                    owner,
                                    type,
                                    parseUpgrades(rs.getString(3)),
                                    rs.getInt(4),
                                    state,
                                    rs.getBytes(6)));
                }
            }
        }
        return rows;
    }

    /**
     * Purchase: REJECTED if the same type already exists (the ledger deduction is rolled back too).
     */
    public static long insert(Connection c, UUID owner, int season, HarborId type, int durability)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM ships WHERE owner = ? AND season = ? AND type = ?")) {
            ps.setObject(1, owner);
            ps.setInt(2, season);
            ps.setString(3, type.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new LedgerException(LedgerError.REJECTED, "이미 가진 선박: " + type);
                }
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO ships (owner, season, type, upgrades, durability, state) VALUES (?, ?, ?, '{}', ?, 'STORED')",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, owner);
            ps.setInt(2, season);
            ps.setString(3, type.toString());
            ps.setInt(4, durability);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /** Tutorial ship (unchanged if present). true if newly inserted. */
    public static boolean insertStarter(
            Connection c, UUID owner, int season, HarborId type, int durability)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO ships (owner, season, type, upgrades, durability, state) VALUES (?, ?, ?, '{}', ?, 'STORED')")) {
            ps.setObject(1, owner);
            ps.setInt(2, season);
            ps.setString(3, type.toString());
            ps.setInt(4, durability);
            return ps.executeUpdate() > 0;
        }
    }

    public static void updateState(Connection c, long shipId, int durability, ShipState state)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE ships SET durability = ?, state = ? WHERE ship_id = ?")) {
            ps.setInt(1, durability);
            ps.setString(2, state.name());
            ps.setLong(3, shipId);
            ps.executeUpdate();
        }
    }

    public static void updateCargo(Connection c, long shipId, byte @Nullable [] cargo)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE ships SET cargo = ? WHERE ship_id = ?")) {
            ps.setBytes(1, cargo);
            ps.setLong(2, shipId);
            ps.executeUpdate();
        }
    }

    /**
     * Upgrade: changes only if the read value is unchanged (prevents concurrent purchases).
     * Otherwise REJECTED.
     */
    public static void upgrade(
            Connection c,
            long shipId,
            Map<UpgradePath, Integer> before,
            Map<UpgradePath, Integer> after,
            int durability)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE ships SET upgrades = ?, durability = ? WHERE ship_id = ? AND upgrades = ?")) {
            ps.setString(1, json(after));
            ps.setInt(2, durability);
            ps.setLong(3, shipId);
            ps.setString(4, json(before));
            if (ps.executeUpdate() != 1) {
                throw new LedgerException(LedgerError.REJECTED, "업그레이드 상태가 바뀌었습니다: " + shipId);
            }
        }
    }

    static Map<UpgradePath, Integer> parseUpgrades(@Nullable String raw) {
        Map<UpgradePath, Integer> result = new EnumMap<>(UpgradePath.class);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            for (UpgradePath p : UpgradePath.values()) {
                if (json.has(p.key())) {
                    result.put(p, json.get(p.key()).getAsInt());
                }
            }
        } catch (RuntimeException e) {
            // Malformed values are treated as no upgrades
        }
        return result;
    }

    /** JSON with a fixed path order ('{}' or {"speed":1,…}; step 0 is omitted). */
    static String json(Map<UpgradePath, Integer> upgrades) {
        JsonObject json = new JsonObject();
        for (UpgradePath p : UpgradePath.values()) {
            int level = upgrades.getOrDefault(p, 0);
            if (level > 0) {
                json.addProperty(p.key(), level);
            }
        }
        return json.toString();
    }
}
