package wiki.creeper.harbor.quest.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.logic.Progress;

/** Quest SQL (quest_progress · quest_board). 트랜잭션은 호출하는 쪽이 연다. */
public final class QuestRepository {

    static final String MAIN_LIKE = "harbor:quest/main/%";

    /** 수락 결과. */
    public enum AcceptResult {
        OK,
        /** 이미 진행 중이거나 같은 cycle에 완료함. */
        ALREADY
    }

    /** 관리 명령 /harbor quest info 한 줄. */
    public record Row(QuestId quest, int season, String state, String progress, String updatedAt) {}

    private QuestRepository() {}

    // ------------------------------------------------------------------ 로드

    /** 접속 전 로드: 현재 시즌 행 + 모든 시즌의 메인 행, 게시판 행. */
    public static QuestData load(Connection c, UUID uuid, SeasonId season) throws SQLException {
        Map<QuestId, QuestEntry> entries = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT quest_id, season, state, progress FROM quest_progress"
                                + " WHERE uuid = ? AND (season = ? OR quest_id LIKE ?)"
                                + " ORDER BY season")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, MAIN_LIKE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<QuestId> quest = quest(rs.getString(1));
                    Optional<QuestState> state = state(rs.getString(3));
                    if (quest.isEmpty() || state.isEmpty()) {
                        continue;
                    }
                    // 같은 메인 퀘스트가 여러 시즌에 있으면 나중 시즌 행을 쓴다 (ORDER BY season)
                    entries.put(
                            quest.get(),
                            new QuestEntry(
                                    quest.get(),
                                    rs.getInt(2),
                                    state.get(),
                                    Progress.fromJson(rs.getString(4))));
                }
            }
        }
        Map<QuestType, BoardState> boards = new EnumMap<>(QuestType.class);
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT kind, cycle, offers, chosen FROM quest_board WHERE uuid = ?"
                                + " ORDER BY created_at")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<QuestType> kind = Keyed.fromKey(QuestType.class, rs.getString(1));
                    if (kind.isPresent()) {
                        boards.put(
                                kind.get(),
                                new BoardState(
                                        kind.get(),
                                        rs.getString(2),
                                        ids(rs.getString(3)),
                                        ids(rs.getString(4))));
                    }
                }
            }
        }
        return new QuestData(entries, boards, null);
    }

    // ------------------------------------------------------------------ 상태 전이 (즉시)

    /** 수락: 행을 잠그고, 진행 중이거나 같은 cycle에 완료했으면 거절한다. 다른 cycle의 완료 행·실패 행은 새 진행으로 덮어쓴다. */
    public static AcceptResult accept(
            Connection c, UUID uuid, int season, QuestId quest, Progress progress)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT state, progress FROM quest_progress WHERE uuid = ? AND season = ? AND quest_id = ? FOR UPDATE")) {
            bindKey(ps, uuid, season, quest);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String state = rs.getString(1);
                    Progress old = Progress.fromJson(rs.getString(2));
                    boolean open = state.equals("ACTIVE") || state.equals("READY");
                    boolean sameCycle =
                            state.equals("COMPLETED") && old.cycle().equals(progress.cycle());
                    if (open || sameCycle) {
                        return AcceptResult.ALREADY;
                    }
                }
            }
        }
        upsert(c, uuid, season, quest, QuestState.ACTIVE, progress);
        return AcceptResult.OK;
    }

    public static void upsert(
            Connection c, UUID uuid, int season, QuestId quest, QuestState state, Progress progress)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO quest_progress (uuid, season, quest_id, state, progress) VALUES (?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE state = VALUES(state), progress = VALUES(progress)")) {
            bindKey(ps, uuid, season, quest);
            ps.setString(4, state.name());
            ps.setString(5, progress.toJson());
            ps.executeUpdate();
        }
    }

    /** 지연 쓰기 큐의 진행 저장. 진행 중(ACTIVE·READY) 행만 바꾼다: 그 사이 완료·포기·만료로 즉시 쓴 상태를 오래된 진행이 덮어쓰지 않게. */
    public static void saveProgress(
            Connection c, UUID uuid, int season, QuestId quest, QuestState state, Progress progress)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE quest_progress SET state = ?, progress = ?"
                                + " WHERE uuid = ? AND season = ? AND quest_id = ? AND state IN ('ACTIVE', 'READY')")) {
            ps.setString(1, state.name());
            ps.setString(2, progress.toJson());
            ps.setString(3, uuid.toString());
            ps.setInt(4, season);
            ps.setString(5, quest.toString());
            ps.executeUpdate();
        }
    }

    /** 완료 (보고 2단계): READY·ACTIVE 행만 COMPLETED로 바꾼다. 다른 서버·이전 보고가 먼저 끝냈으면 false. */
    public static boolean complete(
            Connection c, UUID uuid, int season, QuestId quest, Progress progress)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE quest_progress SET state = 'COMPLETED', progress = ?"
                                + " WHERE uuid = ? AND season = ? AND quest_id = ? AND state IN ('ACTIVE', 'READY')")) {
            ps.setString(1, progress.toJson());
            ps.setString(2, uuid.toString());
            ps.setInt(3, season);
            ps.setString(4, quest.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** 지급 기록 갱신 (완료 행). */
    public static void savePaid(
            Connection c, UUID uuid, int season, QuestId quest, Progress progress)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE quest_progress SET progress = ?"
                                + " WHERE uuid = ? AND season = ? AND quest_id = ? AND state = 'COMPLETED'")) {
            ps.setString(1, progress.toJson());
            ps.setString(2, uuid.toString());
            ps.setInt(3, season);
            ps.setString(4, quest.toString());
            ps.executeUpdate();
        }
    }

    /** 상태만 바꾼다 (이벤트 기간 종료 FAILED, 관리 명령). */
    public static void setState(
            Connection c, UUID uuid, int season, QuestId quest, QuestState state)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE quest_progress SET state = ? WHERE uuid = ? AND season = ? AND quest_id = ?")) {
            ps.setString(1, state.name());
            ps.setString(2, uuid.toString());
            ps.setInt(3, season);
            ps.setString(4, quest.toString());
            ps.executeUpdate();
        }
    }

    /** 포기·만료·관리자 초기화 (행 삭제). */
    public static void delete(Connection c, UUID uuid, int season, QuestId quest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM quest_progress WHERE uuid = ? AND season = ? AND quest_id = ?")) {
            bindKey(ps, uuid, season, quest);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 게시판

    /** 게시판 행을 쓰고, 같은 종류의 지난 cycle 행은 지운다. */
    public static void saveBoard(Connection c, UUID uuid, BoardState board) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO quest_board (uuid, kind, cycle, offers, chosen) VALUES (?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE offers = VALUES(offers), chosen = VALUES(chosen)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, board.kind().key());
            ps.setString(3, board.cycle());
            ps.setString(4, json(board.offers()));
            ps.setString(5, json(board.chosen()));
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM quest_board WHERE uuid = ? AND kind = ? AND cycle <> ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, board.kind().key());
            ps.setString(3, board.cycle());
            ps.executeUpdate();
        }
    }

    public static void deleteBoard(Connection c, UUID uuid, QuestType kind, String cycle)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM quest_board WHERE uuid = ? AND kind = ? AND cycle = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind.key());
            ps.setString(3, cycle);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 서버 진행도

    /** 이번 시즌에 이 퀘스트를 완료한 인원. */
    public static long completedCount(Connection c, QuestId quest, int season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(DISTINCT uuid) FROM quest_progress WHERE quest_id = ? AND season = ? AND state = 'COMPLETED'")) {
            ps.setString(1, quest.toString());
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * 장 클리어 인원 (docs 20 6.4): 이번 시즌에 final 퀘스트를 완료했거나, 연대기 회상 플래그(이번 시즌 player_flags)가 있는 인원. Core
     * 소유 player_flags는 읽기만 한다.
     */
    public static long chapterClears(Connection c, QuestId finalQuest, String recapFlag, int season)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM ("
                                + " SELECT uuid FROM quest_progress WHERE quest_id = ? AND season = ? AND state = 'COMPLETED'"
                                + " UNION"
                                + " SELECT uuid FROM player_flags WHERE flag = ? AND season = ?"
                                + ") t")) {
            ps.setString(1, finalQuest.toString());
            ps.setInt(2, season);
            ps.setString(3, recapFlag);
            ps.setInt(4, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 시즌 활동 유저 (Core player_season 행 수, 읽기만). */
    public static long activePlayers(Connection c, int season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT COUNT(*) FROM player_season WHERE season = ?")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 관리 명령: 이 플레이어의 모든 행. */
    public static List<Row> rows(Connection c, UUID uuid) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT quest_id, season, state, progress, updated_at FROM quest_progress WHERE uuid = ?"
                                + " ORDER BY season, quest_id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<QuestId> quest = quest(rs.getString(1));
                    if (quest.isPresent()) {
                        rows.add(
                                new Row(
                                        quest.get(),
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        String.valueOf(rs.getTimestamp(5))));
                    }
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ 내부

    private static void bindKey(PreparedStatement ps, UUID uuid, int season, QuestId quest)
            throws SQLException {
        ps.setString(1, uuid.toString());
        ps.setInt(2, season);
        ps.setString(3, quest.toString());
    }

    static Optional<QuestId> quest(String raw) {
        return HarborId.tryParse(raw).filter(id -> id.hasPrefix(QuestId.PREFIX)).map(QuestId::new);
    }

    static Optional<QuestState> state(String raw) {
        try {
            QuestState s = QuestState.valueOf(raw.toUpperCase(Locale.ROOT));
            return s.stored() ? Optional.of(s) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String json(List<QuestId> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(id.toString()));
        return array.toString();
    }

    static List<QuestId> ids(String json) {
        List<QuestId> result = new ArrayList<>();
        try {
            JsonParser.parseString(json)
                    .getAsJsonArray()
                    .forEach(e -> quest(e.getAsString()).ifPresent(result::add));
        } catch (JsonParseException | IllegalStateException e) {
            // 깨진 행은 빈 목록 (다음 초기화에 다시 뽑는다)
        }
        return result;
    }
}
