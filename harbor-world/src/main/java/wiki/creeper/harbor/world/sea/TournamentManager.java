package wiki.creeper.harbor.world.sea;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.fishing.api.CatchSource;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.SeaEventType;
import wiki.creeper.harbor.world.content.SeaEventDef;

/**
 * Weekly fishing tournament (docs 20 0.1: scheduled by the World sea event scheduler, ranked by the
 * Season RankingService). For each species caught with a rod during the tournament, adds rarity
 * score × (1 + quality bonus × (★ − 1)) directly to tournament_score (an increment rather than an
 * absolute value so it carries over across servers). Season reads the leaderboard as the source
 * {@code tournament}, and when it ends the scheduler server pays rank rewards (ledger
 * TOURNAMENT_REWARD, reference ID tour:&lt;run ID&gt;).
 */
public final class TournamentManager implements Listener {

    /** Ranking source (called by Season RankingSource, virtual thread). */
    public record Row(UUID uuid, String name, int score, int catches, Instant updatedAt) {}

    private final WorldKit kit;
    private final java.util.Map<String, ActiveSeaEvent> running = new java.util.HashMap<>();

    public TournamentManager(WorldKit kit) {
        this.kit = kit;
    }

    void begin(ActiveSeaEvent event) {
        running.put(event.runId(), event);
    }

    /** End: rewards are paid by the server that ran the scheduler ({@link #payRewards}). */
    void end(ActiveSeaEvent event, boolean finished) {
        running.remove(event.runId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCatch(FishCaughtEvent event) {
        if (running.isEmpty() || event.source() != CatchSource.ROD) {
            return;
        }
        Player player = event.getPlayer();
        for (ActiveSeaEvent run : running.values()) {
            if (!run.covers(event.region())) {
                continue;
            }
            Optional<SeaEventDef.Tournament> cfg =
                    kit.api()
                            .content()
                            .snapshot()
                            .get(SeaEventDef.TYPE, run.eventId())
                            .map(SeaEventDef::tournament);
            if (cfg.isEmpty()) {
                continue;
            }
            int stars = event.item().quality().stars();
            int points =
                    (int)
                            Math.round(
                                    cfg.get().points(event.fish().grade())
                                            * (1 + cfg.get().qualityBonus() * (stars - 1)));
            if (points <= 0) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            String name = player.getName();
            int season = kit.season();
            String runId = run.runId();
            var _ =
                    kit.api()
                            .database()
                            .asyncVoid(c -> add(c, runId, uuid, season, name, points))
                            .exceptionally(
                                    e -> {
                                        kit.log().warn("대회 점수 기록 실패: {}", String.valueOf(e));
                                        return null;
                                    });
            kit.actionBar(
                    player,
                    "world.tournament.points",
                    Placeholder.unparsed("points", String.valueOf(points)));
        }
    }

    static void add(Connection c, String runId, UUID uuid, int season, String name, int points)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO tournament_score (run_id, uuid, season, name, score, catches) VALUES (?, ?, ?, ?, ?, 1) "
                                + "ON DUPLICATE KEY UPDATE score = score + VALUES(score), catches = catches + 1, name = VALUES(name)")) {
            ps.setString(1, runId);
            ps.setObject(2, uuid);
            ps.setInt(3, season);
            ps.setString(4, name);
            ps.setInt(5, points);
            ps.executeUpdate();
        }
    }

    /** Top limit players of the most recent tournament this season (archive if schema is given). */
    public static List<Row> top(Connection c, int season, @Nullable String schema, int limit)
            throws SQLException {
        String table = (schema == null ? "" : schema + ".") + "tournament_score";
        String run = null;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT run_id FROM "
                                + table
                                + " WHERE season = ? ORDER BY updated_at DESC LIMIT 1")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    run = rs.getString(1);
                }
            }
        }
        if (run == null) {
            return List.of();
        }
        return runTop(c, table, run, limit);
    }

    private static List<Row> runTop(Connection c, String table, String run, int limit)
            throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, name, score, catches, updated_at FROM "
                                + table
                                + " WHERE run_id = ? ORDER BY score DESC, updated_at ASC LIMIT ?")) {
            ps.setString(1, run);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp t = rs.getTimestamp(5);
                    rows.add(
                            new Row(
                                    rs.getObject(1, UUID.class),
                                    rs.getString(2),
                                    rs.getInt(3),
                                    rs.getInt(4),
                                    t == null ? Instant.EPOCH : t.toInstant()));
                }
            }
        }
        return rows;
    }

    /**
     * Rank rewards (scheduler server, after 30 seconds so other servers finish recording their last
     * scores). The reference ID is unique per run, so nothing is paid twice.
     */
    void payRewards(String runId, SeaEventDef def) {
        SeaEventDef.Tournament cfg = def.tournament();
        if (cfg == null || cfg.rewards().isEmpty()) {
            return;
        }
        int last = cfg.rewards().getLast().to();
        var _ =
                kit.tasks()
                        .later(
                                20L * 30,
                                () -> {
                                    var _ =
                                            kit.api()
                                                    .database()
                                                    .async(
                                                            c ->
                                                                    runTop(
                                                                            c,
                                                                            "tournament_score",
                                                                            runId,
                                                                            last))
                                                    .thenAcceptAsync(
                                                            rows -> reward(runId, cfg, rows),
                                                            kit.tasks().main());
                                });
    }

    private void reward(String runId, SeaEventDef.Tournament cfg, List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            int rank = i + 1;
            Row row = rows.get(i);
            long gold = 0;
            for (SeaEventDef.Reward r : cfg.rewards()) {
                if (rank >= r.from() && rank <= r.to()) {
                    gold = r.gold();
                }
            }
            if (gold <= 0) {
                continue;
            }
            long amount = gold;
            var _ =
                    kit.api()
                            .currency()
                            .give(
                                    row.uuid(),
                                    Currency.GOLD,
                                    amount,
                                    LedgerReason.TOURNAMENT_REWARD,
                                    "tour:" + runId)
                            .thenAccept(
                                    result -> {
                                        Player p = Bukkit.getPlayer(row.uuid());
                                        if (result.isOk() && p != null) {
                                            kit.send(
                                                    p,
                                                    "world.tournament.reward",
                                                    Placeholder.unparsed(
                                                            "rank", String.valueOf(rank)),
                                                    Placeholder.unparsed(
                                                            "reward", String.valueOf(amount)));
                                        }
                                    });
        }
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "UPDATE tournament_score SET rewarded = TRUE WHERE run_id = ?")) {
                                        ps.setString(1, runId);
                                        ps.executeUpdate();
                                    }
                                });
    }

    public boolean isRunning() {
        return !running.isEmpty();
    }

    static boolean isTournament(SeaEventDef def) {
        return def.type() == SeaEventType.TOURNAMENT;
    }
}
