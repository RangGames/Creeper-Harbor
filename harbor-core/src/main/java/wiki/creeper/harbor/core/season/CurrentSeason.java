package wiki.creeper.harbor.core.season;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Current season number from season_meta ('11. Data design' 3.1, docs 20 11.4). The Season module
 * owns season_meta and moves a season to ACTIVE at the end of a transition (OPEN step); Core reads
 * it at startup so a server restarted after a transition runs the new season without editing
 * harbor-infra.yml.
 *
 * <p>Rules (proposed): season.source: config forces the configured season.id. Otherwise the ACTIVE
 * row wins (the highest one if several), then a TRANSITIONING row (a server started during
 * maintenance keeps the season being closed). With no table or no such row (fresh database, Season
 * not installed), the configured season.id is used.
 */
public final class CurrentSeason {

    /** Wait for the startup read (blocks onEnable like migrations do). */
    private static final long STARTUP_TIMEOUT_S = 10;

    /** One season_meta row. */
    public record Row(int season, Instant startsAt, String state) {}

    private CurrentSeason() {}

    /**
     * Resolves the season at startup. Runs the query on a virtual thread and waits for it (startup
     * only, like migrations). Returns {@code infra} unchanged when the DB row agrees, is missing or
     * the read fails.
     */
    public static InfraConfig resolve(InfraConfig infra, Database db, Logger log) {
        if (!infra.seasonFromDb()) {
            log.info("season.source: config, 설정의 시즌 {}을(를) 씁니다", infra.season());
            return infra;
        }
        Optional<Row> row;
        try {
            row = db.async(CurrentSeason::read).get(STARTUP_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return infra;
        } catch (ExecutionException | TimeoutException e) {
            log.warn(
                    "season_meta를 읽지 못해 설정의 시즌 {}을(를) 씁니다: {}",
                    infra.season(),
                    String.valueOf(e.getMessage()));
            return infra;
        }
        if (row.isEmpty()) {
            return infra;
        }
        Row r = row.get();
        if (r.season() == infra.season().value()) {
            return infra;
        }
        LocalDate start = r.startsAt().atZone(infra.zone()).toLocalDate();
        log.warn(
                "season_meta의 {} 시즌 {}을(를) 씁니다 (harbor-infra.yml season.id {}, 시작일 {})",
                r.state(),
                r.season(),
                infra.season(),
                start);
        return infra.withSeason(new SeasonId(r.season()), start);
    }

    /**
     * Reads the current season row (blocking). Empty without the table (Season not installed yet)
     * or without an ACTIVE/TRANSITIONING row.
     */
    public static Optional<Row> read(Connection c) throws SQLException {
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT season, starts_at, state FROM season_meta"
                                        + " WHERE state IN ('ACTIVE', 'TRANSITIONING')"
                                        + " ORDER BY state = 'ACTIVE' DESC, season DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            Timestamp start = rs.getTimestamp(2);
            return Optional.of(
                    new Row(
                            rs.getInt(1),
                            start == null ? Instant.now() : start.toInstant(),
                            rs.getString(3)));
        } catch (SQLException e) {
            // 42S02 = table missing (Season module not installed / not migrated yet)
            if ("42S02".equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
