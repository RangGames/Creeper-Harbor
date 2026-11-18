package wiki.creeper.harbor.world.sea;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.SeaEventDef;

/**
 * 해상 이벤트 스케줄러 (docs 20 8.4 1~4단계). 잠금 harbor:lock:event를 얻은 서버 하나가 1분마다 events.yml의 시간대·확률·최소 간격을
 * 확인한다. 발동 계획은 {@link EventPlanner}가 날짜로 정해 어느 서버에서도 같고, 상태 전이(WARN → ACTIVE → ENDED)는
 * sea_event_log 행 갱신이 성공한 서버만 신호를 보내므로 두 서버가 겹쳐도 한 번만 나간다.
 *
 * <ul>
 *   <li>WARN: warn_min 전 (물때 읽기 알림을 위해 1분 먼저 보내고 서버마다 시각에 맞춰 알린다). SeaEventSignal WARN
 *   <li>START: sea_event_log ACTIVE, SeaEventSignal START + Fishing용 SeaEventMessage
 *       START(fish_effects), harbor:announce 공지
 *   <li>END: ENDED, SeaEventSignal END + SeaEventMessage END. 대회면 순위 보상
 * </ul>
 */
public final class SeaEventScheduler {

    private static final String LOCK = "harbor:lock:event";
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final WorldKit kit;
    private final SeaEventManager events;
    private final TournamentManager tournaments;
    private volatile boolean running;

    public SeaEventScheduler(WorldKit kit, SeaEventManager events, TournamentManager tournaments) {
        this.kit = kit;
        this.events = events;
        this.tournaments = tournaments;
    }

    public void start() {
        long period = 20L * Math.max(10, kit.balance().eventCheckSeconds());
        var _ = kit.tasks().timer(200, period, this::check);
    }

    private record Outgoing(SeaEventSignal signal, SeaEventDef def) {}

    /** 메인 스레드: 정의와 서버 플래그를 모아 가상 스레드로 넘긴다. */
    private void check() {
        if (running) {
            return;
        }
        List<SeaEventDef> defs =
                new ArrayList<>(kit.api().content().snapshot().all(SeaEventDef.TYPE).values());
        if (defs.isEmpty()) {
            return;
        }
        Map<HarborId, Boolean> flags = new HashMap<>();
        for (SeaEventDef def : defs) {
            String flag = def.requiresFlag();
            flags.put(def.id(), flag == null || kit.api().flags().hasServer(flag));
        }
        int season = kit.season();
        ZoneId zone = kit.api().time().zone();
        long ttl = Math.max(5, kit.balance().eventCheckSeconds() - 5);
        running = true;
        var _ =
                kit.tasks()
                        .supplyAsync(
                                () -> {
                                    Optional<DistributedLock.Handle> lock =
                                            kit.api()
                                                    .locks()
                                                    .tryAcquire(LOCK, Duration.ofSeconds(ttl));
                                    if (lock.isEmpty()) {
                                        return List.<Outgoing>of();
                                    }
                                    try {
                                        return kit.api()
                                                .database()
                                                .tx(c -> plan(c, defs, flags, season, zone));
                                    } catch (java.sql.SQLException e) {
                                        throw new java.util.concurrent.CompletionException(e);
                                    }
                                })
                        .whenCompleteAsync(
                                (out, error) -> {
                                    running = false;
                                    if (error != null) {
                                        kit.log().warn("해상 이벤트 스케줄 실패: {}", String.valueOf(error));
                                        return;
                                    }
                                    out.forEach(o -> publish(o.signal(), o.def()));
                                },
                                kit.tasks().main());
    }

    private static List<Outgoing> plan(
            java.sql.Connection c,
            List<SeaEventDef> defs,
            Map<HarborId, Boolean> flags,
            int season,
            ZoneId zone)
            throws java.sql.SQLException {
        Instant now = Instant.now();
        List<Outgoing> out = new ArrayList<>();
        Map<String, SeaEventDef> byId = new HashMap<>();
        defs.forEach(d -> byId.put(d.id().toString(), d));
        // 1) 열린 실행의 상태 전이
        for (SeaEventRepository.Row row : SeaEventRepository.open(c)) {
            SeaEventDef def = byId.get(row.eventId());
            if (def == null) {
                continue;
            }
            if (row.state().equals("WARN") && !now.isBefore(row.startedAt())) {
                if (SeaEventRepository.transition(
                        c, row.runId(), List.of("WARN"), "ACTIVE", null)) {
                    out.add(new Outgoing(signal(row, SeaEventSignal.START), def));
                }
            } else if (row.state().equals("ACTIVE") && !now.isBefore(row.endsAt())) {
                if (SeaEventRepository.transition(
                        c, row.runId(), List.of("ACTIVE"), "ENDED", null)) {
                    out.add(new Outgoing(signal(row, SeaEventSignal.END), def));
                }
            }
        }
        // 2) 새 실행 (예고 1분 전부터)
        Map<String, Instant> last = SeaEventRepository.lastStarts(c);
        Map<String, Integer> counts = SeaEventRepository.seasonCounts(c, season);
        for (SeaEventDef def : defs) {
            for (EventPlanner.Plan plan : EventPlanner.openPlans(def, now.plusSeconds(60), zone)) {
                if (!now.isBefore(plan.endAt())) {
                    continue;
                }
                String key = def.id().toString();
                if (!EventPlanner.allowed(
                        plan,
                        last.get(key),
                        counts.getOrDefault(key, 0),
                        flags.getOrDefault(def.id(), true))) {
                    continue;
                }
                boolean active = !now.isBefore(plan.startAt());
                String region = plan.region() == null ? null : plan.region().toString();
                if (SeaEventRepository.insert(
                        c,
                        plan.runId(),
                        key,
                        region,
                        season,
                        plan.warnAt(),
                        plan.startAt(),
                        plan.endAt(),
                        active ? "ACTIVE" : "WARN",
                        false)) {
                    SeaEventSignal warn =
                            new SeaEventSignal(
                                    plan.runId(),
                                    key,
                                    SeaEventSignal.WARN,
                                    region,
                                    plan.warnAt(),
                                    plan.startAt(),
                                    plan.endAt());
                    out.add(new Outgoing(warn, def));
                    if (active) {
                        out.add(
                                new Outgoing(
                                        new SeaEventSignal(
                                                plan.runId(),
                                                key,
                                                SeaEventSignal.START,
                                                region,
                                                plan.warnAt(),
                                                plan.startAt(),
                                                plan.endAt()),
                                        def));
                    }
                    last.put(key, plan.startAt());
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    private static SeaEventSignal signal(SeaEventRepository.Row row, String state) {
        return new SeaEventSignal(
                row.runId(),
                row.eventId(),
                state,
                row.region(),
                row.warnAt(),
                row.startedAt(),
                row.endsAt());
    }

    /** 신호를 보내고 이 서버에도 바로 반영한다 (Redis 메아리는 같은 결과라 무시된다). */
    private void publish(SeaEventSignal signal, SeaEventDef def) {
        kit.api().bus().publish(SeaEventSignal.CHANNEL, signal);
        events.apply(signal, true);
        switch (signal.state()) {
            case SeaEventSignal.START -> {
                kit.api()
                        .bus()
                        .publish(
                                SeaEventMessage.CHANNEL,
                                new SeaEventMessage(
                                        signal.runId(),
                                        SeaEventMessage.State.START,
                                        signal.region(),
                                        signal.endsAt(),
                                        def.fishEffects()));
                String regionName =
                        Optional.ofNullable(signal.region())
                                .flatMap(r -> HarborId.tryParse(r))
                                .flatMap(r -> kit.index().get(r))
                                .map(e -> e.def().name())
                                .orElse("");
                String text =
                        MiniMessage.miniMessage()
                                .serialize(
                                        kit.api()
                                                .messages()
                                                .get(
                                                        "world.event.announce",
                                                        Placeholder.parsed("name", def.name()),
                                                        Placeholder.parsed("region", regionName)));
                kit.api().bus().publish(Channels.ANNOUNCEMENTS, new Channels.Announcement(text));
            }
            case SeaEventSignal.END, SeaEventSignal.CANCEL -> {
                kit.api()
                        .bus()
                        .publish(
                                SeaEventMessage.CHANNEL,
                                new SeaEventMessage(
                                        signal.runId(),
                                        SeaEventMessage.State.END,
                                        signal.region(),
                                        signal.endsAt(),
                                        List.of()));
                if (signal.state().equals(SeaEventSignal.END)
                        && TournamentManager.isTournament(def)) {
                    tournaments.payRewards(signal.runId(), def);
                }
            }
            default -> {
                // WARN은 신호만
            }
        }
    }

    // ------------------------------------------------------------------ 관리 명령

    /** 수동 발동 (관리자 Dialog 패널과 같은 기능). 지금 바로 ACTIVE로 시작한다. */
    public CompletableFuture<Optional<String>> startNow(
            SeaEventDef def, @Nullable RegionId region, int minutes) {
        Instant now = Instant.now();
        RegionId target =
                region != null ? region : def.regions().isEmpty() ? null : def.regions().getFirst();
        String runId = def.key() + "_" + RUN.format(now.atZone(kit.api().time().zone())) + "m";
        Instant end = now.plus(Duration.ofMinutes(minutes > 0 ? minutes : def.durationMinutes()));
        String regionText = target == null ? null : target.toString();
        String key = def.id().toString();
        int season = kit.season();
        return kit.api()
                .database()
                .async(
                        c ->
                                SeaEventRepository.insert(
                                        c,
                                        runId,
                                        key,
                                        regionText,
                                        season,
                                        now,
                                        now,
                                        end,
                                        "ACTIVE",
                                        true))
                .thenApplyAsync(
                        inserted -> {
                            if (!inserted) {
                                return Optional.<String>empty();
                            }
                            SeaEventSignal warn =
                                    new SeaEventSignal(
                                            runId,
                                            key,
                                            SeaEventSignal.WARN,
                                            regionText,
                                            now,
                                            now,
                                            end);
                            publish(warn, def);
                            publish(
                                    new SeaEventSignal(
                                            runId,
                                            key,
                                            SeaEventSignal.START,
                                            regionText,
                                            now,
                                            now,
                                            end),
                                    def);
                            return Optional.of(runId);
                        },
                        kit.tasks().main());
    }

    /** 수동 중단 (취소: 대회 보상 없음). */
    public CompletableFuture<Boolean> stop(String runId) {
        Optional<wiki.creeper.harbor.world.api.ActiveSeaEvent> e =
                events.all().stream().filter(x -> x.runId().equals(runId)).findFirst();
        Optional<SeaEventDef> def =
                e.flatMap(x -> kit.api().content().snapshot().get(SeaEventDef.TYPE, x.eventId()));
        Instant now = Instant.now();
        return kit.api()
                .database()
                .async(
                        c ->
                                SeaEventRepository.transition(
                                        c, runId, List.of("WARN", "ACTIVE"), "CANCELLED", now))
                .thenApplyAsync(
                        changed -> {
                            if (e.isPresent() && def.isPresent()) {
                                var a = e.get();
                                String region =
                                        a.region() == null ? null : String.valueOf(a.region());
                                publish(
                                        new SeaEventSignal(
                                                runId,
                                                a.eventId().toString(),
                                                SeaEventSignal.CANCEL,
                                                region,
                                                a.startsAt(),
                                                a.startsAt(),
                                                now),
                                        def.get());
                            }
                            return changed;
                        },
                        kit.tasks().main());
    }
}
