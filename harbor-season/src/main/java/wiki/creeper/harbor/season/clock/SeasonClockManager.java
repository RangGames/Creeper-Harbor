package wiki.creeper.harbor.season.clock;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.api.event.DailyResetEvent;
import wiki.creeper.harbor.season.api.event.SeasonWeekChangedEvent;
import wiki.creeper.harbor.season.api.event.WeeklyResetEvent;
import wiki.creeper.harbor.season.content.SeasonDef;
import wiki.creeper.harbor.season.data.SeasonMeta;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.net.SeasonMessages;

/**
 * SeasonClock 구현 (docs 20 11.4). 주차는 콘텐츠(seasons.yml)와 season_meta의 주차 보정으로 메모리에서 계산하고, 1분마다 바뀌었는지
 * 보고 이벤트를 낸다. season_meta는 시작할 때와 5분마다, 그리고 harbor:season MetaChanged를 받으면 다시 읽는다.
 */
public final class SeasonClockManager implements SeasonClock {

    private final HarborApi api;
    private final SeasonContentView content;
    private final Tasks tasks;
    private final Logger log;
    private final List<IntConsumer> weeklyListeners = new ArrayList<>();

    /** 콘텐츠 스냅샷이 바뀌거나 보정이 바뀌면 다시 만든다. */
    private record Cached(ContentSnapshot snapshot, int shift, SeasonCalendar calendar) {}

    private volatile @Nullable Cached cached;
    private volatile int shiftHours;
    private volatile SeasonPhase phase = SeasonPhase.ACTIVE;

    // 메인 스레드에서만 쓰는 상태
    private int lastWeek = -1;
    private @Nullable ZonedDateTime nextDaily;
    private @Nullable ZonedDateTime nextWeekly;

    public SeasonClockManager(HarborApi api, SeasonContentView content, Tasks tasks, Logger log) {
        this.api = api;
        this.content = content;
        this.tasks = tasks;
        this.log = log;
    }

    /** 주간 초기화 때 부를 내부 동작 (랭킹 주간 스냅샷). 인자는 끝난 주차. */
    public void onWeeklyReset(IntConsumer listener) {
        weeklyListeners.add(listener);
    }

    public void start() {
        int checkTicks = content.balance().clockCheckSeconds() * 20;
        var _ = tasks.timer(40, checkTicks, this::tick);
        int metaTicks = content.balance().metaReloadSeconds() * 20;
        var _ =
                tasks.timer(
                        metaTicks,
                        metaTicks,
                        () -> {
                            var _ = reloadMeta();
                        });
        api.bus()
                .subscribe(
                        SeasonMessages.META,
                        envelope -> {
                            if (envelope.payload().season() == current().value()) {
                                var _ = reloadMeta();
                            }
                        });
        var _ = initMeta();
    }

    /** season_meta에 지금 시즌과 계획된 시즌 행을 만들고 상태를 읽는다. */
    private CompletableFuture<Void> initMeta() {
        int season = current().value();
        SeasonCalendar cal = calendar();
        List<SeasonDef> planned =
                content.defs().seasons().stream().filter(d -> d.season() > season).toList();
        InfraConfig infra = api.infra();
        return api.database()
                .asyncVoid(
                        c -> {
                            boolean anyActive =
                                    SeasonRepository.allMeta(c).stream()
                                            .anyMatch(
                                                    m ->
                                                            m.season() != season
                                                                    && (m.state()
                                                                                    == SeasonPhase
                                                                                            .ACTIVE
                                                                            || m.state()
                                                                                    == SeasonPhase
                                                                                            .TRANSITIONING));
                            SeasonRepository.ensureMeta(
                                    c,
                                    season,
                                    cal.startsAt().toInstant(),
                                    cal.endsAt().toInstant(),
                                    anyActive ? SeasonPhase.PLANNED : SeasonPhase.ACTIVE);
                            for (SeasonDef def : planned) {
                                ZonedDateTime start =
                                        def.startsAt(infra.zone(), infra.dailyReset());
                                Instant end = start.plusWeeks(def.weeks()).toInstant();
                                SeasonRepository.ensureMeta(
                                        c,
                                        def.season(),
                                        start.toInstant(),
                                        end,
                                        SeasonPhase.PLANNED);
                                SeasonRepository.updatePlanned(
                                        c, def.season(), start.toInstant(), end);
                            }
                        })
                .thenCompose(ignored -> reloadMeta())
                .exceptionally(
                        e -> {
                            log.error("season_meta 초기화 실패", e);
                            return null;
                        });
    }

    /** season_meta를 다시 읽어 상태·보정을 반영한다. */
    public CompletableFuture<Void> reloadMeta() {
        int season = current().value();
        return api.database()
                .async(c -> SeasonRepository.meta(c, season))
                .thenAcceptAsync(
                        meta -> {
                            if (meta.isEmpty()) {
                                return;
                            }
                            SeasonMeta m = meta.get();
                            if (m.state() != phase) {
                                log.info("시즌 {} 상태: {} → {}", season, phase, m.state());
                            }
                            phase = m.state();
                            shiftHours = Math.max(0, m.weekShiftHours());
                            if (phase == SeasonPhase.PLANNED || phase == SeasonPhase.ARCHIVED) {
                                log.error(
                                        "harbor-infra.yml의 시즌 {}이(가) season_meta에서 {} 상태입니다. 시즌 전환을 마쳤는지, 서버 설정의 season.id가 맞는지 확인하세요",
                                        season,
                                        phase);
                            }
                        },
                        tasks.main())
                .exceptionally(
                        e -> {
                            log.warn("season_meta 읽기 실패: {}", String.valueOf(e.getMessage()));
                            return null;
                        });
    }

    /** 전환 엔진이 상태를 바꾼 뒤 부른다 (다른 서버에도 알림). */
    public void publishMetaChanged() {
        api.bus().publish(SeasonMessages.META, new SeasonMessages.MetaChanged(current().value()));
        var _ = reloadMeta();
    }

    /**
     * 주차 시작을 늦춘다 (관리자 명령). 줄이는 것은 거부한다.
     *
     * @return 바뀌었으면 true (메인 스레드에서 완료)
     */
    public CompletableFuture<Boolean> shiftWeek(int hours) {
        int season = current().value();
        return api.database()
                .async(c -> SeasonRepository.shiftWeek(c, season, hours))
                .thenApplyAsync(
                        ok -> {
                            if (ok) {
                                shiftHours = hours;
                                publishMetaChanged();
                            }
                            return ok;
                        },
                        tasks.main());
    }

    private void tick() {
        int week = week();
        int season = current().value();
        if (lastWeek >= 0 && week != lastWeek) {
            log.info("시즌 {} 주차 변경: {} → {}", season, lastWeek, week);
            Bukkit.getPluginManager()
                    .callEvent(new SeasonWeekChangedEvent(current(), lastWeek, week));
        }
        lastWeek = week;

        ZonedDateTime now = api.time().now();
        ZonedDateTime daily = nextDaily;
        if (daily == null) {
            nextDaily = api.time().nextDailyReset();
        } else if (!now.isBefore(daily)) {
            nextDaily = api.time().nextDailyReset();
            Bukkit.getPluginManager().callEvent(new DailyResetEvent(api.time().gameDay()));
        }
        ZonedDateTime weekly = nextWeekly;
        if (weekly == null) {
            nextWeekly = api.time().nextWeeklyReset();
        } else if (!now.isBefore(weekly)) {
            nextWeekly = api.time().nextWeeklyReset();
            Bukkit.getPluginManager().callEvent(new WeeklyResetEvent(current(), week));
            int finished = Math.max(0, calendar().weekAt(weekly.toInstant().minusSeconds(1)));
            for (IntConsumer listener : weeklyListeners) {
                try {
                    listener.accept(finished);
                } catch (RuntimeException e) {
                    log.error("주간 초기화 처리 중 오류", e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ SeasonClock

    /** 지금 콘텐츠·보정 기준 달력. */
    public SeasonCalendar calendar() {
        ContentSnapshot snap = content.snapshot();
        int shift = shiftHours;
        Cached c = cached;
        if (c != null && c.snapshot() == snap && c.shift() == shift) {
            return c.calendar();
        }
        SeasonCalendar cal = build(snap, shift);
        cached = new Cached(snap, shift, cal);
        return cal;
    }

    private SeasonCalendar build(ContentSnapshot snap, int shift) {
        InfraConfig infra = api.infra();
        int season = infra.season().value();
        Optional<SeasonDef> def = SeasonContentView.defs(snap).get(season);
        if (def.isPresent()) {
            SeasonDef d = def.get();
            return new SeasonCalendar(
                    season,
                    d.name(),
                    d.startsAt(infra.zone(), infra.dailyReset()),
                    d.weeks(),
                    shift);
        }
        ZonedDateTime start = infra.seasonStart().atTime(infra.dailyReset()).atZone(infra.zone());
        return new SeasonCalendar(season, "S" + season, start, 12, shift);
    }

    @Override
    public SeasonId current() {
        return api.infra().season();
    }

    @Override
    public int week() {
        return calendar().weekAt(Instant.now());
    }

    @Override
    public int weeks() {
        return calendar().weeks();
    }

    @Override
    public ZonedDateTime weekStart(int week) {
        return calendar().weekStart(week);
    }

    @Override
    public ZonedDateTime startsAt() {
        return calendar().startsAt();
    }

    @Override
    public ZonedDateTime endsAt() {
        return calendar().endsAt();
    }

    @Override
    public int weekShiftHours() {
        return shiftHours;
    }

    @Override
    public ZonedDateTime nextDailyReset() {
        return api.time().nextDailyReset();
    }

    @Override
    public ZonedDateTime nextWeeklyReset() {
        return api.time().nextWeeklyReset();
    }

    @Override
    public SeasonPhase state() {
        return phase;
    }

    @Override
    public String displayName() {
        return calendar().name();
    }
}
