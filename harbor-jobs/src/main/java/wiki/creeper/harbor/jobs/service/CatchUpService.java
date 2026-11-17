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
 * 캐치업 (docs 20 2.4 CatchUpService). 매일 06:00 한 서버만 최근 7일 접속자의 레벨 중앙값을 계산한다. 시즌 5주차 이상이고 레벨 ≤ 중앙값 −
 * 10이면 exp_gain +0.5, 9주차 이상이면 +1.0 ('7. 밸런스 기획' 3.3).
 *
 * <p>중앙값은 문서의 Redis 키(harbor:jobs:median) 대신 Core FlagService의 서버 플래그 {@code jobs/median}에 둔다.
 * FlagService가 DB에 저장하고 Redis로 다른 서버에 변경을 알리므로, 각 서버는 ServerFlagChangedEvent로 다시 계산한다. 값이 없으면(장애)
 * 마지막 값을 유지한다.
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

    /** 가상 스레드에서 실행: 분산 잠금을 잡은 서버만 계산한다. */
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

    /** 플레이어의 캐치업 보정자를 다시 건다 (접속·레벨업·중앙값 갱신 때). */
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
