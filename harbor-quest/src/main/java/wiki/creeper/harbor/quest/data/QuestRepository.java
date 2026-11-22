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

/** Quest SQL (quest_progress · quest_board). The caller opens the transaction. */
public final class QuestRepository {

    static final String MAIN_LIKE = "harbor:quest/main/%";

    /** Accept result. */
    public enum AcceptResult {
        OK,
        /** Already in progress or completed in the same cycle. */
        ALREADY
    }

    /** One line of the admin command /harbor quest info. */
    public record Row(QuestId quest, int season, String state, String progress, String updatedAt) {}

    private QuestRepository() {}

    // ------------------------------------------------------------------ Loading

    /** Pre-login load: current season rows + main rows of every season, board rows. */
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
                    // If the same main quest exists in several seasons, the later season's row is
                    // used (ORDER BY season)
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

    // ------------------------------------------------------------------ State transitions
    // (immediate)

    /**
     * Accept: locks the row and refuses if it is in progress or was completed in the same cycle.
     * Completed rows of another cycle and failed rows are overwritten with new progress.
     */
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

    /**
     * Progress save from the write-behind queue. Only changes in-progress (ACTIVE, READY) rows, so
     * stale progress never overwrites a state written immediately in the meantime (complete,
     * abandon, expire).
     */
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

    /**
     * Complete (turn-in step 2): only changes READY or ACTIVE rows to COMPLETED. false if another
     * server or an earlier turn-in finished first.
     */
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

    /** Updates the payout record (completed row). */
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

    /** Changes only the state (FAILED when the event period ends, admin commands). */
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

    /** Abandon, expire or admin reset (deletes the row). */
    public static void delete(Connection c, UUID uuid, int season, QuestId quest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM quest_progress WHERE uuid = ? AND season = ? AND quest_id = ?")) {
            bindKey(ps, uuid, season, quest);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Board

    /** Writes the board row and deletes rows of past cycles of the same kind. */
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

    // ------------------------------------------------------------------ Server progress

    /** Number of players who completed this quest this season. */
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
     * Chapter clear count (docs 20 6.4): players who completed the final quest this season or have
     * the chronicle recap flag (player_flags of this season). Core-owned player_flags is only read.
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

    /** Season active players (row count of Core player_season, read only). */
    public static long activePlayers(Connection c, int season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT COUNT(*) FROM player_season WHERE season = ?")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Admin command: all rows of this player. */
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

    // ------------------------------------------------------------------ Internal

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
            // A broken row becomes an empty list (rolled again on the next reset)
        }
        return result;
    }
}
