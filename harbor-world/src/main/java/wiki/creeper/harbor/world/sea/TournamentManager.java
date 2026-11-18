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
 * 주간 낚시 대회 (docs 20 0.1: 일정은 World 해상 이벤트 스케줄러, 순위 집계는 Season RankingService). 대회 중 낚싯대로 잡은 어종마다 등급
 * 점수 × (1 + 품질 보너스 × (★ − 1))를 tournament_score에 바로 더한다 (서버를 옮겨도 이어지도록 절댓값이 아닌 증가분). 순위표는 Season이
 * 원천 {@code tournament}로 읽고, 끝나면 스케줄러 서버가 순위 보상(원장 TOURNAMENT_REWARD, 참조 ID tour:&lt;실행 ID&gt;)을
 * 준다.
 */
public final class TournamentManager implements Listener {

    /** 순위 원천 (Season RankingSource가 부른다, 가상 스레드). */
    public record Row(UUID uuid, String name, int score, int catches, Instant updatedAt) {}

    private final WorldKit kit;
    private final java.util.Map<String, ActiveSeaEvent> running = new java.util.HashMap<>();

    public TournamentManager(WorldKit kit) {
        this.kit = kit;
    }

    void begin(ActiveSeaEvent event) {
        running.put(event.runId(), event);
    }

    /** 끝: 보상은 스케줄러를 돌린 서버가 준다 ({@link #payRewards}). */
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

    /** 이번 시즌 가장 최근 대회의 상위 limit명 (schema가 있으면 아카이브). */
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

    /** 순위 보상 (스케줄러 서버, 다른 서버의 마지막 점수 기록이 끝나도록 30초 뒤). 참조 ID가 실행마다 유니크라 두 번 지급되지 않는다. */
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
