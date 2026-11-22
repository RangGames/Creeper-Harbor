package wiki.creeper.harbor.fishing.sea;

import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.db.Database;

/**
 * 신화 서버 마릿수 카운터 (docs 20 3.2, 문서의 Redis 키 harbor:mythic:<이벤트>:<어종>). Core가 다른 모듈에 Redis 원자 연산을 열어
 * 주지 않아서 DB 테이블 fishing_mythic_counter(추가 제안)의 조건부 UPDATE로 같은 효과(INCR로 예약, 한도 초과면 실패, 실패 시 DECR)를
 * 낸다. 모든 서버가 같은 DB를 보므로 서버 간에도 원자적이다. Core에 Redis 카운터 API가 생기면 이 클래스만 바꾼다.
 */
public final class MythicCounter {

    private final Database database;

    public MythicCounter(Database database) {
        this.database = database;
    }

    /** 한 마리를 예약한다. 한도를 넘으면 false. */
    public CompletableFuture<Boolean> reserve(String eventId, String fishId, int cap) {
        return database.async(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT IGNORE INTO fishing_mythic_counter (event_id, fish_id, count) VALUES (?, ?, 0)")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE fishing_mythic_counter SET count = count + 1"
                                            + " WHERE event_id = ? AND fish_id = ? AND count < ?")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.setInt(3, cap);
                        return ps.executeUpdate() == 1;
                    }
                });
    }

    /** 예약을 돌려준다 (미니게임 실패·미리 감기·중단). */
    public CompletableFuture<Void> release(String eventId, String fishId) {
        return database.asyncVoid(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE fishing_mythic_counter SET count = count - 1"
                                            + " WHERE event_id = ? AND fish_id = ? AND count > 0")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.executeUpdate();
                    }
                });
    }

    /** 서버 종료용: 기다려서 한 마리를 반납한다. 실패는 기록만 한다. */
    public void releaseNow(String eventId, String fishId) {
        try {
            database.txVoid(
                    c -> {
                        try (PreparedStatement ps =
                                c.prepareStatement(
                                        "UPDATE fishing_mythic_counter SET count = count - 1"
                                                + " WHERE event_id = ? AND fish_id = ? AND count > 0")) {
                            ps.setString(1, eventId);
                            ps.setString(2, fishId);
                            ps.executeUpdate();
                        }
                    });
        } catch (java.sql.SQLException e) {
            System.getLogger("Harbor/Fishing")
                    .log(
                            System.Logger.Level.WARNING,
                            "신화 예약 반납 실패 (종료 중): " + eventId + " " + fishId,
                            e);
        }
    }

    /** 현재 마릿수 (관리 명령). */
    public CompletableFuture<Integer> count(String eventId, String fishId) {
        return database.async(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT count FROM fishing_mythic_counter WHERE event_id = ? AND fish_id = ?")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        try (var rs = ps.executeQuery()) {
                            return rs.next() ? rs.getInt(1) : 0;
                        }
                    }
                });
    }
}
