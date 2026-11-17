package wiki.creeper.harbor.core.item;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.core.api.HarborItem;

/** 고유 ID 장부 (fish_ledger). ★5·전설·신화 어종과 보스 장비를 생성할 때 기록하고, 경매 등록·거래 때 상태를 대조해 복사된 아이템을 막는다. */
public final class FishLedger {

    /** 장부 상태. */
    public enum State {
        ACTIVE,
        LISTED,
        RELEASED,
        /** 수족관에 기부됨 (Collection). */
        DONATED,
        /** NPC에게 판매됨 (Economy). */
        SOLD,
        /** 경매에 등록됨 (Economy). */
        IN_AUCTION,
        DESTROYED
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

    public static Optional<State> state(Connection c, UUID uid) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT state FROM fish_ledger WHERE item_uuid = ?")) {
            ps.setString(1, uid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(State.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /** 상태를 바꾼다. expected 상태일 때만 바뀌므로, 같은 uid가 두 번 등록·거래되면 두 번째가 실패한다. */
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
