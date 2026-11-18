package wiki.creeper.harbor.shop.pearl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.ledger.LedgerWriter;
import wiki.creeper.harbor.common.model.Currency;

/**
 * 진주 원장 한 건을 이미 열린 트랜잭션 안에서 처리한다 (docs 20 12.4 '쓰기 순서'). Common 원장 도구로 지갑 행 잠금 → 음수 검사 →
 * pearl_ledger 기록(참조 ID 유니크) → 잔액 갱신을 하고, 같은 트랜잭션에서 묶음(pearl_lot) 차감·유료 잔액·처리 후 잔액 열을 맞춘다. 실패하면
 * {@link LedgerException}을 던지고 호출한 쪽 트랜잭션이 통째로 롤백된다.
 *
 * <p>골드·물비늘로 바꾸는 함수는 없다 (경제 분리, '6. BM 기획' 1장).
 */
public final class PearlLedger {

    /**
     * 변경 한 건.
     *
     * @param refId 참조 ID (필수). 같은 (uuid, 사유, 참조 ID)는 한 번만 기록된다
     * @param paid 지급일 때 유료 묶음인지 (결제·구독 월 진주 = 유료, 패스·이벤트·관리자 = 무료)
     * @param lotRef 지급 묶음의 source_ref (null이면 refId)
     * @param fromLot 차감할 묶음을 지정 (환불·지불 거절 회수). null이면 무료 먼저·오래된 유료 순
     */
    public record Change(
            UUID player,
            long delta,
            LedgerReason reason,
            String refId,
            boolean paid,
            @Nullable String lotRef,
            @Nullable Long fromLot) {

        public static Change credit(
                UUID player, long amount, LedgerReason reason, String refId, boolean paid) {
            return new Change(player, amount, reason, refId, paid, null, null);
        }

        public static Change debit(UUID player, long amount, LedgerReason reason, String refId) {
            return new Change(player, -amount, reason, refId, false, null, null);
        }
    }

    /** 처리 결과. */
    public record Applied(UUID player, long balance, long paidBalance, long paidDelta) {}

    private final PearlWalletStore store = new PearlWalletStore();
    private final LedgerWriter writer = new LedgerWriter(Map.of(Currency.PEARL, store));

    public PearlWalletStore store() {
        return store;
    }

    public Applied apply(Connection c, Change change) throws SQLException {
        long balance =
                writer.apply(
                        c,
                        new LedgerEntry(
                                change.player(),
                                Currency.PEARL,
                                change.delta(),
                                change.reason(),
                                change.refId()));
        long paidDelta;
        if (change.delta() > 0) {
            insertLot(
                    c,
                    change.player(),
                    change.lotRef() != null ? change.lotRef() : change.refId(),
                    change.paid(),
                    change.delta());
            paidDelta = change.paid() ? change.delta() : 0;
        } else {
            List<PearlLots.Take> takes = plan(c, change);
            for (PearlLots.Take take : takes) {
                try (PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE pearl_lot SET remaining = remaining - ? WHERE lot_id = ? AND remaining >= ?")) {
                    ps.setLong(1, take.amount());
                    ps.setLong(2, take.lotId());
                    ps.setLong(3, take.amount());
                    if (ps.executeUpdate() != 1) {
                        throw new LedgerException(LedgerError.REJECTED, "묶음 차감 실패: " + take);
                    }
                }
            }
            paidDelta = -PearlLots.paidPart(takes);
        }
        long paidBalance;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE pearl_wallet SET paid_balance = paid_balance + ? WHERE uuid = ?")) {
            ps.setLong(1, paidDelta);
            ps.setString(2, change.player().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement("SELECT paid_balance FROM pearl_wallet WHERE uuid = ?")) {
            ps.setString(1, change.player().toString());
            try (ResultSet rs = ps.executeQuery()) {
                paidBalance = rs.next() ? rs.getLong(1) : 0;
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE pearl_ledger SET balance_after = ?, paid_delta = ?"
                                + " WHERE uuid = ? AND reason = ? AND ref_id = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, paidDelta);
            ps.setString(3, change.player().toString());
            ps.setString(4, change.reason().name());
            ps.setString(5, change.refId());
            ps.executeUpdate();
        }
        return new Applied(change.player(), balance, paidBalance, paidDelta);
    }

    /** 여러 건을 순서대로 처리한다 (10회 개봉, 선물 등). */
    public List<Applied> applyAll(Connection c, List<Change> changes) throws SQLException {
        List<Applied> result = new ArrayList<>();
        for (Change change : changes) {
            result.add(apply(c, change));
        }
        return result;
    }

    private static List<PearlLots.Take> plan(Connection c, Change change) throws SQLException {
        long amount = -change.delta();
        List<PearlLots.Lot> lots = new ArrayList<>();
        String sql =
                change.fromLot() == null
                        ? "SELECT lot_id, paid, remaining FROM pearl_lot WHERE uuid = ? AND remaining > 0 FOR UPDATE"
                        : "SELECT lot_id, paid, remaining FROM pearl_lot WHERE uuid = ? AND lot_id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, change.player().toString());
            if (change.fromLot() != null) {
                ps.setLong(2, change.fromLot());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lots.add(new PearlLots.Lot(rs.getLong(1), rs.getBoolean(2), rs.getLong(3)));
                }
            }
        }
        try {
            return PearlLots.allocate(lots, amount);
        } catch (IllegalArgumentException e) {
            // 지갑 잔액은 충분했는데 묶음이 모자라면 불변식이 깨진 것. 쓰지 않고 거부한다 (일일 검사가 알려 준다)
            throw new LedgerException(LedgerError.REJECTED, "묶음 잔량 불일치: " + change);
        }
    }

    private static void insertLot(
            Connection c, UUID player, String sourceRef, boolean paid, long amount)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO pearl_lot (uuid, source_ref, paid, amount, remaining) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, sourceRef);
            ps.setBoolean(3, paid);
            ps.setLong(4, amount);
            ps.setLong(5, amount);
            ps.executeUpdate();
        }
    }
}
