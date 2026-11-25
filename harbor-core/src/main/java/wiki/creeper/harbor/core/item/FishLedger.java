package wiki.creeper.harbor.core.item;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * Unique ID ledger (fish_ledger). Records ★5, legendary and mythic fish and boss gear when created,
 * and checks their status on auction listing and trades to block duplicated items.
 *
 * <p>Fish rows fill fish_id, size and quality; non-fish unique items (boss gear) fill item_id and
 * leave those empty ('11. Data design' 3.2). catcher is the player the item was first given to.
 */
public final class FishLedger {

    /** Ledger status. */
    public enum State {
        ACTIVE,
        LISTED,
        RELEASED,
        /** Donated to the aquarium (Collection). */
        DONATED,
        /** Sold to an NPC (Economy). */
        SOLD,
        /** Listed on the auction (Economy). */
        IN_AUCTION,
        /** Gifted to an NPC (NPC affinity). */
        GIFTED,
        DESTROYED,
        /** Item of a past season, closed by the season transition (Season reset_plan.yml). */
        ARCHIVED;

        /** Parses a stored state. Unknown values read as DESTROYED (never usable). */
        public static State parse(String raw) {
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                return DESTROYED;
            }
        }
    }

    private FishLedger() {}

    public static void insert(Connection c, UUID uid, HarborItem.Fish fish) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO fish_ledger (item_uuid, fish_id, size, quality, catcher, season) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid.toString());
            ps.setString(2, fish.species().toString());
            ps.setBigDecimal(
                    3, BigDecimal.valueOf(fish.sizeCm()).setScale(1, RoundingMode.HALF_UP));
            ps.setInt(4, fish.quality().stars());
            ps.setString(5, fish.catcher().uuid().toString());
            ps.setInt(6, fish.season().value());
            ps.executeUpdate();
        }
    }

    /**
     * Records a non-fish unique item (boss gear etc.) with item_id; fish_id, size and quality stay
     * empty. Blocking (call inside Database.async/tx).
     *
     * @param owner the player the item was created for
     */
    public static void insertItem(Connection c, UUID uid, ItemId item, UUID owner, SeasonId season)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO fish_ledger (item_uuid, item_id, catcher, season) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uid.toString());
            ps.setString(2, item.toString());
            ps.setString(3, owner.toString());
            ps.setInt(4, season.value());
            ps.executeUpdate();
        }
    }

    public static Optional<State> state(Connection c, UUID uid) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT state FROM fish_ledger WHERE item_uuid = ?")) {
            ps.setString(1, uid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(State.parse(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /**
     * Changes the status. It only changes from the expected status, so if the same uid is listed or
     * traded twice the second attempt fails.
     */
    public static boolean transition(Connection c, UUID uid, State expected, State next)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE fish_ledger SET state = ? WHERE item_uuid = ? AND state = ?")) {
            ps.setString(1, next.name());
            ps.setString(2, uid.toString());
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        }
    }
}
