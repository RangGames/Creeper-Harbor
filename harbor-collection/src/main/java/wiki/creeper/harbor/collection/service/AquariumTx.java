package wiki.creeper.harbor.collection.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.item.FishLedger;

/** SQL steps of the aquarium donation transaction ({@link AquariumManager#donateItems}). */
final class AquariumTx {

    private AquariumTx() {}

    /**
     * Unique ID ledger: ACTIVE -> DONATED. Accepted as is if Quest already set it to DONATED in the
     * same delivery.
     */
    static void ledger(Connection c, @Nullable UUID uid, boolean questOwned) throws SQLException {
        if (uid == null
                || FishLedger.transition(
                        c, uid, FishLedger.State.ACTIVE, FishLedger.State.DONATED)) {
            return;
        }
        if (questOwned
                && FishLedger.state(c, uid)
                        .filter(s -> s == FishLedger.State.DONATED)
                        .isPresent()) {
            return;
        }
        throw new SQLException("고유 ID 장부 상태가 ACTIVE가 아닙니다: " + uid);
    }

    static void lockSeason(Connection c, SeasonId season) throws SQLException {
        try (var ps =
                c.prepareStatement(
                        "INSERT INTO aquarium_state (season, points, species_count) VALUES (?, 0, 0)"
                                + " ON DUPLICATE KEY UPDATE season = season")) {
            ps.setInt(1, season.value());
            ps.executeUpdate();
        }
        try (var ps =
                c.prepareStatement(
                        "SELECT season FROM aquarium_state WHERE season = ? FOR UPDATE")) {
            ps.setInt(1, season.value());
            try (var rs = ps.executeQuery()) {
                // Only takes the lock
                var _ = rs.next();
            }
        }
    }

    static boolean speciesDonated(Connection c, SeasonId season, FishId fish) throws SQLException {
        try (var ps =
                c.prepareStatement(
                        "SELECT 1 FROM aquarium_species WHERE season = ? AND fish_id = ?")) {
            ps.setInt(1, season.value());
            ps.setString(2, fish.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
