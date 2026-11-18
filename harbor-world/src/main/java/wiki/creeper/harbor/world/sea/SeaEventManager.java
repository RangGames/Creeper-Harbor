package wiki.creeper.harbor.world.sea;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.SeaEventType;
import wiki.creeper.harbor.world.api.event.SeaEventEndEvent;
import wiki.creeper.harbor.world.api.event.SeaEventStartEvent;
import wiki.creeper.harbor.world.api.event.SeaEventWarnEvent;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 해상 이벤트의 서버별 상태와 효과 (docs 20 8.4 '해상 이벤트 스케줄러' 2~4단계). 스케줄러(한 서버)가 harbor:event로 보낸
 * WARN·START·END를 모든 서버가 받아 같은 상태를 갖고, 이 서버에 있는 지역에만 효과를 준다.
 *
 * <ul>
 *   <li>WARN: 예고 문구 (탐험 3차 '물때 읽기' event_forewarn_s 만큼 먼저), 수평선 번개 연출
 *   <li>START: 전역 보정자(출처 event:&lt;이름&gt;, 만료 = 종료 시각), SeaEventStartEvent, 폭풍·유령선·대회 시작
 *   <li>END: 보정자 해제, 개인 날씨 복구, 해적 정리, SeaEventEndEvent
 * </ul>
 *
 * 재시작하면 sea_event_log에서 진행 중인 이벤트를 다시 등록하고, END를 못 받아도 종료 시각이 지나면 스스로 끝낸다. 메인 스레드 전용.
 */
public final class SeaEventManager implements SeaEventService, Listener {

    private final WorldKit kit;
    private final RegionManager regions;
    private final Map<String, ActiveSeaEvent> events = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> warned = new HashMap<>();
    private final Set<UUID> stormy = new HashSet<>();
    private final Map<String, Integer> stormTicks = new HashMap<>();
    private @Nullable GhostShipController ghosts;
    private @Nullable TournamentManager tournaments;

    public SeaEventManager(WorldKit kit, RegionManager regions) {
        this.kit = kit;
        this.regions = regions;
    }

    public void controllers(GhostShipController ghost, TournamentManager tournament) {
        this.ghosts = ghost;
        this.tournaments = tournament;
    }

    public void start() {
        kit.api()
                .bus()
                .subscribe(
                        SeaEventSignal.CHANNEL,
                        envelope -> {
                            var _ = kit.tasks().sync(() -> apply(envelope.payload(), true));
                        });
        var _ = kit.tasks().timer(20, 20, this::tick);
        var _ =
                kit.api()
                        .database()
                        .async(SeaEventRepository::open)
                        .thenAcceptAsync(
                                rows -> {
                                    Instant now = Instant.now();
                                    for (SeaEventRepository.Row r : rows) {
                                        if (!now.isBefore(r.endsAt())) {
                                            continue;
                                        }
                                        String state =
                                                r.state().equals("ACTIVE")
                                                        ? SeaEventSignal.START
                                                        : SeaEventSignal.WARN;
                                        apply(
                                                new SeaEventSignal(
                                                        r.runId(),
                                                        r.eventId(),
                                                        state,
                                                        r.region(),
                                                        r.warnAt(),
                                                        r.startedAt(),
                                                        r.endsAt()),
                                                false);
                                    }
                                },
                                kit.tasks().main())
                        .exceptionally(
                                e -> {
                                    kit.log().warn("해상 이벤트 복구 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }

    public void shutdown() {
        for (ActiveSeaEvent e : List.copyOf(events.values())) {
            cleanup(e);
        }
        events.clear();
    }

    // ------------------------------------------------------------------ 신호 처리

    /** 신호를 반영한다. 같은 신호가 두 번 와도(내가 보낸 것 + Redis 메아리) 결과가 같다. */
    public void apply(SeaEventSignal signal, boolean announce) {
        Optional<HarborId> id = HarborId.tryParse(signal.eventId());
        Optional<SeaEventDef> def =
                id.flatMap(h -> kit.api().content().snapshot().get(SeaEventDef.TYPE, h));
        if (def.isEmpty()) {
            return;
        }
        RegionId region = null;
        String raw = signal.region();
        if (raw != null && !raw.isBlank()) {
            try {
                region = RegionId.parse(raw);
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        ActiveSeaEvent current = events.get(signal.runId());
        switch (signal.state()) {
            case SeaEventSignal.WARN -> {
                if (current != null) {
                    return;
                }
                ActiveSeaEvent e = view(signal, def.get(), region, ActiveSeaEvent.Phase.WARN);
                events.put(signal.runId(), e);
                Bukkit.getPluginManager().callEvent(new SeaEventWarnEvent(e));
            }
            case SeaEventSignal.START -> {
                if (current != null && current.phase() == ActiveSeaEvent.Phase.ACTIVE) {
                    return;
                }
                ActiveSeaEvent e = view(signal, def.get(), region, ActiveSeaEvent.Phase.ACTIVE);
                events.put(signal.runId(), e);
                begin(e, def.get(), announce);
            }
            case SeaEventSignal.END, SeaEventSignal.CANCEL -> {
                if (current == null) {
                    return;
                }
                events.remove(signal.runId());
                end(current, signal.state().equals(SeaEventSignal.END));
            }
            default -> {
                // 모르는 상태는 무시
            }
        }
    }

    private static ActiveSeaEvent view(
            SeaEventSignal s,
            SeaEventDef def,
            @Nullable RegionId region,
            ActiveSeaEvent.Phase phase) {
        return new ActiveSeaEvent(
                s.runId(),
                def.id(),
                def.name(),
                def.type(),
                region,
                def.area(),
                s.startsAt(),
                s.endsAt(),
                phase,
                def.wildlifeBoost(),
                def.fishEffects());
    }

    private void begin(ActiveSeaEvent e, SeaEventDef def, boolean announce) {
        String source = "event:" + def.key();
        for (SeaEventDef.ModifierSpec m : def.modifiers()) {
            StatKey stat = StatKey.of(m.stat());
            Modifier mod =
                    m.multiply()
                            ? Modifier.multiply(stat, m.value(), source)
                            : Modifier.add(stat, m.value(), source);
            kit.api().modifiers().addGlobal(mod.until(e.endsAt()));
        }
        if (announce) {
            for (Player p : affected(e)) {
                kit.send(
                        p,
                        "world.event.start",
                        Placeholder.parsed("name", def.name()),
                        Placeholder.unparsed("minutes", String.valueOf(def.durationMinutes())));
            }
        }
        GhostShipController g = ghosts;
        if (g != null && e.type() == SeaEventType.GHOST_SHIP) {
            g.begin(e, def);
        }
        TournamentManager t = tournaments;
        if (t != null && e.type() == SeaEventType.TOURNAMENT) {
            t.begin(e);
        }
        Bukkit.getPluginManager().callEvent(new SeaEventStartEvent(e));
    }

    private void end(ActiveSeaEvent e, boolean finished) {
        cleanup(e);
        warned.remove(e.runId());
        for (Player p : affected(e)) {
            kit.send(p, "world.event.end", Placeholder.parsed("name", e.name()));
        }
        TournamentManager t = tournaments;
        if (t != null && e.type() == SeaEventType.TOURNAMENT) {
            t.end(e, finished);
        }
        Bukkit.getPluginManager().callEvent(new SeaEventEndEvent(e));
    }

    private void cleanup(ActiveSeaEvent e) {
        kit.api()
                .content()
                .snapshot()
                .get(SeaEventDef.TYPE, e.eventId())
                .ifPresent(def -> kit.api().modifiers().removeGlobalSource("event:" + def.key()));
        if (e.type() == SeaEventType.STORM) {
            for (UUID uuid : List.copyOf(stormy)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null
                        && stormAt(WorldKit.loc(p))
                                .filter(x -> !x.runId().equals(e.runId()))
                                .isEmpty()) {
                    p.resetPlayerWeather();
                    stormy.remove(uuid);
                }
            }
        }
        GhostShipController g = ghosts;
        if (g != null && e.type() == SeaEventType.GHOST_SHIP) {
            g.end(e);
        }
    }

    /** 이 서버에서 이벤트 해역에 있는 플레이어 (해역이 없으면 전원). */
    private List<Player> affected(ActiveSeaEvent e) {
        List<Player> result = new ArrayList<>();
        RegionId region = e.region();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (region == null
                    || regions.current(p.getUniqueId()).filter(region::equals).isPresent()) {
                result.add(p);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 매초

    private void tick() {
        Instant now = Instant.now();
        for (ActiveSeaEvent e : List.copyOf(events.values())) {
            if (!now.isBefore(e.endsAt())) {
                events.remove(e.runId());
                end(e, true);
                continue;
            }
            if (e.phase() == ActiveSeaEvent.Phase.WARN) {
                warn(e, now);
                if (!now.isBefore(e.startsAt())) {
                    // START를 아직 못 받았다 (Redis 지연): 시각이 지났으니 스스로 시작한다
                    kit.api()
                            .content()
                            .snapshot()
                            .get(SeaEventDef.TYPE, e.eventId())
                            .ifPresent(
                                    def -> {
                                        ActiveSeaEvent active =
                                                e.withPhase(ActiveSeaEvent.Phase.ACTIVE);
                                        events.put(e.runId(), active);
                                        begin(active, def, true);
                                    });
                }
            }
        }
        storms();
    }

    /** 예고: 물때 읽기(event_forewarn_s)가 있으면 그만큼 먼저. 수평선 번개 연출. */
    private void warn(ActiveSeaEvent e, Instant now) {
        Set<UUID> done = warned.computeIfAbsent(e.runId(), r -> new HashSet<>());
        long warnMinutes =
                Math.max(1, (e.startsAt().getEpochSecond() - now.getEpochSecond() + 59) / 60);
        Instant warnAt =
                e.startsAt()
                        .minusSeconds(
                                kit.api()
                                        .content()
                                        .snapshot()
                                        .get(SeaEventDef.TYPE, e.eventId())
                                        .map(d -> d.warnMinutes() * 60L)
                                        .orElse(300L));
        for (Player p : affected(e)) {
            if (done.contains(p.getUniqueId())) {
                continue;
            }
            double forewarn = kit.stat(p, "event_forewarn_s", 0);
            if (now.isBefore(warnAt.minusSeconds((long) forewarn))) {
                continue;
            }
            done.add(p.getUniqueId());
            kit.send(
                    p,
                    forewarn > 0 ? "world.event.forewarn" : "world.event.warn",
                    Placeholder.parsed("name", e.name()),
                    Placeholder.unparsed("minutes", String.valueOf(warnMinutes)));
            Location at = WorldKit.loc(p);
            World world = p.getWorld();
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            var _ =
                    world.strikeLightningEffect(
                            at.clone().add(Math.cos(angle) * 90, 0, Math.sin(angle) * 90));
        }
    }

    /** 폭풍: 해역 안 플레이어 개인 날씨, 낙뢰 (폭풍 항해 2랭크 피해 −50%). */
    private void storms() {
        Set<UUID> inStorm = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Optional<ActiveSeaEvent> storm = stormAt(WorldKit.loc(p));
            if (storm.isEmpty()) {
                continue;
            }
            inStorm.add(p.getUniqueId());
            if (stormy.add(p.getUniqueId())) {
                p.setPlayerWeather(WeatherType.DOWNFALL);
                kit.actionBar(p, "world.event.storm_enter");
            }
        }
        for (UUID uuid : List.copyOf(stormy)) {
            if (!inStorm.contains(uuid)) {
                stormy.remove(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.resetPlayerWeather();
                }
            }
        }
        for (ActiveSeaEvent e : events.values()) {
            if (e.type() != SeaEventType.STORM || e.phase() != ActiveSeaEvent.Phase.ACTIVE) {
                continue;
            }
            Optional<SeaEventDef.Storm> cfg =
                    kit.api()
                            .content()
                            .snapshot()
                            .get(SeaEventDef.TYPE, e.eventId())
                            .map(SeaEventDef::storm);
            if (cfg.isEmpty() || cfg.get().lightningIntervalSeconds() <= 0) {
                continue;
            }
            int t = stormTicks.merge(e.runId(), 1, Integer::sum);
            if (t % cfg.get().lightningIntervalSeconds() != 0) {
                continue;
            }
            List<Player> targets = new ArrayList<>();
            for (UUID uuid : inStorm) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null
                        && stormAt(WorldKit.loc(p))
                                .filter(x -> x.runId().equals(e.runId()))
                                .isPresent()) {
                    targets.add(p);
                }
            }
            if (targets.isEmpty()) {
                continue;
            }
            Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            Location at = WorldKit.loc(target);
            boolean hit = ThreadLocalRandom.current().nextDouble() < 0.25;
            double off = hit ? 0.5 : 8 + ThreadLocalRandom.current().nextDouble() * 12;
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            var _ =
                    target.getWorld()
                            .strikeLightningEffect(
                                    at.clone()
                                            .add(Math.cos(angle) * off, 0, Math.sin(angle) * off));
            if (hit) {
                double damage =
                        cfg.get().lightningDamage() * kit.stat(target, "debuff_damage", 1.0);
                if (kit.stat(target, "feature_storm_sailing", 0) >= 2) {
                    damage *= 0.5;
                }
                target.damage(damage);
            }
        }
        stormTicks.keySet().removeIf(run -> !events.containsKey(run));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stormy.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ SeaEventService

    @Override
    public List<ActiveSeaEvent> active() {
        return events.values().stream()
                .filter(e -> e.phase() == ActiveSeaEvent.Phase.ACTIVE)
                .toList();
    }

    @Override
    public List<ActiveSeaEvent> active(RegionId region) {
        return active().stream().filter(e -> e.covers(region)).toList();
    }

    @Override
    public List<ActiveSeaEvent> upcoming() {
        return events.values().stream()
                .filter(e -> e.phase() == ActiveSeaEvent.Phase.WARN)
                .toList();
    }

    @Override
    public Optional<ActiveSeaEvent> stormAt(Location location) {
        Optional<WorldIndex.Entry> region = regions.entryAt(location);
        for (ActiveSeaEvent e : events.values()) {
            if (e.type() != SeaEventType.STORM || e.phase() != ActiveSeaEvent.Phase.ACTIVE) {
                continue;
            }
            RegionId er = e.region();
            if (er == null) {
                return Optional.of(e);
            }
            if (region.isPresent()
                    && region.get().id().equals(er)
                    && region.get()
                            .inArea(e.area(), location.getX(), location.getY(), location.getZ())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** 관리 명령 목록용. */
    public List<ActiveSeaEvent> all() {
        return List.copyOf(events.values());
    }
}
