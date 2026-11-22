package wiki.creeper.harbor.jobs.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.jobs.content.JobsBalance;

/**
 * Catch-up (docs 20 2.4 CatchUpService). Every day at 06:00 a single server computes the median
 * level of players who joined in the last 7 days. From season week 5, if level ≤ median − 10,
 * exp_gain +0.5; from week 9, +1.0 ('7. Balance design' 3.3).
 *
 * <p>The median is kept in the Core FlagService server flag {@code jobs/median} instead of the
 * documented Redis key (harbor:jobs:median). FlagService stores it in the DB and notifies other
 * servers of changes via Redis, so each server recomputes on ServerFlagChangedEvent. If there is no
 * value (outage), the last value is kept.
 */
public final class CatchUpService implements Listener {

    static final String FLAG = "jobs/median";
    private static final String SOURCE = "catchup";
    private static final LocalTime RUN_AT = LocalTime.of(6, 0);

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;

    public CatchUpService(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        schedule();
    }

    private void schedule() {
        long ticks = Math.max(20, api.time().untilNext(RUN_AT).toSeconds() * 20);
        var _ =
                tasks.later(
                        ticks,
                        () -> {
                            var _ = tasks.async(this::recalculate);
                            schedule();
                        });
    }

    public Optional<Integer> median() {
        return api.flags()
                .serverValue(FLAG)
                .flatMap(
                        v -> {
                            try {
                                return Optional.of(Integer.parseInt(v));
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }

    /** Runs on a virtual thread: only the server holding the distributed lock computes. */
    public void recalculate() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:jobs-median", Duration.ofMinutes(10));
        } catch (RuntimeException e) {
            log.warn("캐치업 중앙값 잠금 실패: {}", e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            int season = api.infra().season().value();
            List<Integer> levels =
                    api.database()
                            .tx(
                                    c -> {
                                        List<Integer> result = new ArrayList<>();
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "SELECT s.level FROM player_season s JOIN players p ON p.uuid = s.uuid"
                                                                + " WHERE s.season = ? AND p.last_join >= NOW() - INTERVAL 7 DAY ORDER BY s.level")) {
                                            ps.setInt(1, season);
                                            try (ResultSet rs = ps.executeQuery()) {
                                                while (rs.next()) {
                                                    result.add(rs.getInt(1));
                                                }
                                            }
                                        }
                                        return result;
                                    });
            if (!levels.isEmpty()) {
                int median = levels.get(levels.size() / 2);
                var _ = api.flags().setServer(FLAG, String.valueOf(median), true);
                log.info("캐치업 중앙값 {} (표본 {}명)", median, levels.size());
            }
        } catch (SQLException e) {
            log.error("캐치업 중앙값 계산 실패", e);
        } finally {
            lock.get().release();
        }
    }

    /** Reapplies the player's catch-up modifier (on join, level up and median update). */
    public void apply(UUID uuid) {
        Optional<HarborPlayer> hp = api.players().get(uuid);
        Optional<Integer> median = median();
        api.modifiers().removeSource(uuid, SOURCE);
        if (hp.isEmpty() || median.isEmpty()) {
            return;
        }
        int week = api.time().seasonWeek();
        double bonus = 0;
        JobsBalance balance = new JobsBalance(api.content().snapshot().single(Balance.TYPE));
        for (JobsBalance.CatchUp step : balance.catchUps()) {
            if (week >= step.week() && hp.get().level() <= median.get() - step.gap()) {
                bonus = Math.max(bonus, step.bonus());
            }
        }
        if (bonus > 0) {
            api.modifiers().add(uuid, Modifier.add(JobsManager.EXP_GAIN, bonus, SOURCE));
        }
    }

    @EventHandler
    public void onMedianChanged(ServerFlagChangedEvent event) {
        if (event.flag().equals(FLAG)) {
            api.players().online().forEach(p -> apply(p.uuid()));
        }
    }
}
