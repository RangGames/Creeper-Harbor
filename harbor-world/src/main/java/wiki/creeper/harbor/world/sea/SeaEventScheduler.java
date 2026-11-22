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
 * Sea event scheduler (docs 20 8.4 steps 1–4). The one server holding the lock harbor:lock:event
 * checks the time windows, chances and minimum intervals in events.yml every minute. Firing plans
 * are fixed by date in {@link EventPlanner}, so they are the same on any server, and state
 * transitions (WARN → ACTIVE → ENDED) send a signal only from the server whose sea_event_log row
 * update succeeded, so it goes out once even if two servers overlap.
 *
 * <ul>
 *   <li>WARN: warn_min before (sent 1 minute early for the 'read the tides' notice; each server
 *       announces on time). SeaEventSignal WARN
 *   <li>START: sea_event_log ACTIVE, SeaEventSignal START + SeaEventMessage START (fish_effects)
 *       for Fishing, harbor:announce announcement
 *   <li>END: ENDED, SeaEventSignal END + SeaEventMessage END. Rank rewards for tournaments
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

    /** Main thread: gathers definitions and server flags and hands them to a virtual thread. */
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
        // 1) State transitions of open runs
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
        // 2) New runs (from 1 minute before the announcement)
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

    /**
     * Sends the signal and applies it on this server right away (the Redis echo gives the same
     * result and is ignored).
     */
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
                // WARN only sends the signal
            }
        }
    }

    // ------------------------------------------------------------------ admin commands

    /** Manual start (same as the admin Dialog panel). Starts it as ACTIVE right now. */
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

    /** Manual stop (cancel: no tournament rewards). */
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
