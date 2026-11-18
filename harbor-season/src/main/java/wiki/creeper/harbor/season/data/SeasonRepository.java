package wiki.creeper.harbor.season.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.api.TitleId;

/** season_meta · season_player · titles 읽기·쓰기. 모두 블로킹이므로 가상 스레드에서 부른다. */
public final class SeasonRepository {

    private SeasonRepository() {}

    // ------------------------------------------------------------------ season_meta

    /** 행이 없을 때만 만든다. */
    public static void ensureMeta(
            Connection c, int season, Instant startsAt, Instant endsAt, SeasonPhase state)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO season_meta (season, starts_at, ends_at, state) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, season);
            ps.setTimestamp(2, Timestamp.from(startsAt));
            ps.setTimestamp(3, Timestamp.from(endsAt));
            ps.setString(4, state.name());
            ps.executeUpdate();
        }
    }

    /** 계획된 시즌의 시작일이 바뀌었으면 고친다 (PLANNED 상태만). */
    public static void updatePlanned(Connection c, int season, Instant startsAt, Instant endsAt)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE season_meta SET starts_at = ?, ends_at = ? WHERE season = ? AND state = 'PLANNED'")) {
            ps.setTimestamp(1, Timestamp.from(startsAt));
            ps.setTimestamp(2, Timestamp.from(endsAt));
            ps.setInt(3, season);
            ps.executeUpdate();
        }
    }

    public static Optional<SeasonMeta> meta(Connection c, int season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT season, starts_at, ends_at, state, week_shift_hours FROM season_meta WHERE season = ?")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMeta(rs)) : Optional.empty();
            }
        }
    }

    public static List<SeasonMeta> allMeta(Connection c) throws SQLException {
        List<SeasonMeta> result = new ArrayList<>();
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT season, starts_at, ends_at, state, week_shift_hours FROM season_meta ORDER BY season");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(readMeta(rs));
            }
        }
        return result;
    }

    private static SeasonMeta readMeta(ResultSet rs) throws SQLException {
        Timestamp ends = rs.getTimestamp(3);
        return new SeasonMeta(
                rs.getInt(1),
                rs.getTimestamp(2).toInstant(),
                ends == null ? null : ends.toInstant(),
                SeasonPhase.parse(rs.getString(4)),
                rs.getInt(5));
    }

    /**
     * 상태를 바꾼다. 지금 상태가 expected일 때만 바뀐다 (두 서버가 동시에 전환하지 못하게).
     *
     * @return 바뀌었으면 true
     */
    public static boolean transition(
            Connection c, int season, SeasonPhase expected, SeasonPhase next) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE season_meta SET state = ? WHERE season = ? AND state = ?")) {
            ps.setString(1, next.name());
            ps.setInt(2, season);
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * 주차 보정을 늘린다. 줄이는 것(주차를 앞당기는 것)은 허용하지 않는다.
     *
     * @return 바뀌었으면 true
     */
    public static boolean shiftWeek(Connection c, int season, int hours) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE season_meta SET week_shift_hours = ? WHERE season = ? AND week_shift_hours <= ?")) {
            ps.setInt(1, hours);
            ps.setInt(2, season);
            ps.setInt(3, hours);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ season_player · titles

    /**
     * 플레이어 데이터를 읽는다.
     *
     * <p>season_player 행이 없으면 (Season 모듈이 생기기 전부터 있던 계정이거나 새 계정) 지금 시즌이 이미 시작됐고 그 전에 가입한 사람만 지난 시즌
     * 플레이어로 보고 정리 대상(0)으로 둔다.
     *
     * @param seasonStart 지금 시즌 시작 시각
     */
    public static SeasonProfile loadProfile(
            Connection c, UUID uuid, int currentSeason, Instant seasonStart) throws SQLException {
        int last = -1;
        TitleId equipped = null;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT last_season, equipped_title FROM season_player WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    last = rs.getInt(1);
                    String raw = rs.getString(2);
                    equipped = raw == null ? null : TitleId.tryOf(raw).orElse(null);
                }
            }
        }
        boolean stored = last >= 0;
        if (!stored) {
            last = currentSeason;
            if (!Instant.now().isBefore(seasonStart)) {
                try (PreparedStatement ps =
                        c.prepareStatement("SELECT first_join FROM players WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getTimestamp(1).toInstant().isBefore(seasonStart)) {
                            last = 0;
                        }
                    }
                }
            }
        }
        Set<TitleId> titles = new HashSet<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT title_id FROM titles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TitleId.tryOf(rs.getString(1)).ifPresent(titles::add);
                }
            }
        }
        if (equipped != null && !titles.contains(equipped)) {
            equipped = null;
        }
        return new SeasonProfile(last, equipped, titles, stored);
    }

    public static void saveProfile(Connection c, UUID uuid, SeasonProfile profile)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO season_player (uuid, last_season, equipped_title) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE last_season = GREATEST(last_season, VALUES(last_season)),"
                                + " equipped_title = VALUES(equipped_title)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, profile.lastSeason());
            TitleId equipped = profile.equipped();
            setNullable(ps, 3, equipped == null ? null : equipped.toString());
            ps.executeUpdate();
        }
    }

    static void setNullable(PreparedStatement ps, int index, @Nullable String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** 이름으로 uuid를 찾는다 (오프라인 플레이어 관리 명령). */
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
}
