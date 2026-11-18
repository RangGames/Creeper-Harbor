package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent;
import wiki.creeper.harbor.boss.api.event.BossEncounterStartEvent;
import wiki.creeper.harbor.boss.api.event.BossPhaseChangeEvent;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossProfile;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.Geometry;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.boss.logic.UuidV7;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonServices;

/**
 * 조우 수명 관리 (docs 20 10.4 EncounterManager). 소환 조건 검사 → 준비 → 시작 기록 → 매 틱 진행 → 종료 판정 → 결과·보상 → 정리. 부위
 * 판정(작살 궤적), 입력(QTE·선장), 이탈·재접속·낙수 리스너도 여기 있다. {@link BossService} 구현. 메인 스레드 전용.
 */
public final class EncounterManager implements BossService, Listener {

    /** PlayerComponent&lt;BossProfile&gt;. */
    public static final PlayerComponent<BossProfile> PROFILE =
            new PlayerComponent<>(
                    "boss_profile", BossRepository::loadProfile, BossRepository::saveProfile);

    /** 클릭 대상 바인딩. */
    private record Click(Encounter encounter, Encounter.ClickKind kind, int index) {}

    /** 추적 중인 작살. */
    private static final class Harpoon {
        final UUID thrower;
        final ItemDef def;
        final ItemStack stack;
        Location prev;
        int age;

        Harpoon(UUID thrower, ItemDef def, ItemStack stack, Location prev) {
            this.thrower = thrower;
            this.def = def;
            this.stack = stack;
            this.prev = prev;
        }
    }

    private final HarborApi api;
    private final Plugin plugin;
    private final Tasks tasks;
    private final Logger log;
    private final BossItems items;
    private final PartyManager parties;
    private final FishingService fishing;
    private RewardService rewards;

    private final Map<UUID, Encounter> byId = new LinkedHashMap<>();
    private final Map<UUID, Encounter> byPlayer = new HashMap<>();
    private final Map<UUID, Click> clicks = new HashMap<>();
    private final Map<UUID, Harpoon> harpoons = new HashMap<>();
    private final Map<UUID, Long> summonCooldown = new HashMap<>();
    private final Map<Long, Integer> chunkHolds = new HashMap<>();
    private final Map<UUID, Set<Long>> encounterChunks = new HashMap<>();
    private @Nullable WorldHooks worldHooks;
    private @Nullable InstanceHooks instanceHooks;
    private long tick;

    /** 월드 보스 연결 (WorldBossManager). */
    public interface WorldHooks {
        void stage(EncounterInfo info, int stage, int participants, double percent);

        void ui(Component name, float progress);

        void ended(EncounterInfo info, EncounterState result);
    }

    /** 인스턴스 연결 (InstanceManager). */
    public interface InstanceHooks {
        void ended(UUID encounter, Map<UUID, String> origins, Collection<UUID> members);

        void quit(UUID player, UUID encounter);
    }

    public EncounterManager(
            HarborApi api,
            Plugin plugin,
            Tasks tasks,
            Logger log,
            PartyManager parties,
            FishingService fishing) {
        this.api = api;
        this.plugin = plugin;
        this.tasks = tasks;
        this.log = log;
        this.items = new BossItems(api);
        this.parties = parties;
        this.fishing = fishing;
        this.rewards = new RewardService(this);
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tick);
        recover();
    }

    public void setWorldHooks(@Nullable WorldHooks hooks) {
        this.worldHooks = hooks;
    }

    public void setInstanceHooks(@Nullable InstanceHooks hooks) {
        this.instanceHooks = hooks;
    }

    // ------------------------------------------------------------------ 공용
    // (Encounter·RewardService)

    HarborApi api() {
        return api;
    }

    Plugin plugin() {
        return plugin;
    }

    Tasks tasks() {
        return tasks;
    }

    BossItems items() {
        return items;
    }

    FishingService fishing() {
        return fishing;
    }

    Logger log() {
        return log;
    }

    long now() {
        return tick;
    }

    String serverId() {
        return api.infra().serverId();
    }

    public RewardService rewards() {
        return rewards;
    }

    public BossBalance balance() {
        return new BossBalance(api.content().snapshot().single(Balance.TYPE));
    }

    // ------------------------------------------------------------------ BossService

    @Override
    public Collection<BossDef> definitions() {
        return api.content().snapshot().all(BossDef.TYPE).values();
    }

    @Override
    public Optional<BossDef> definition(BossId boss) {
        return api.content().snapshot().get(BossDef.TYPE, boss.id());
    }

    @Override
    public Optional<EncounterInfo> encounterOf(UUID player) {
        return encounterOfPlayer(player).map(Encounter::info);
    }

    Optional<Encounter> encounterOfPlayer(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Encounter> encounter(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean inEncounter(UUID player) {
        Encounter e = byPlayer.get(player);
        return e != null && !e.finished();
    }

    @Override
    public List<EncounterInfo> encounters() {
        return byId.values().stream().map(Encounter::info).toList();
    }

    public Collection<Encounter> running() {
        return List.copyOf(byId.values());
    }

    @Override
    public StartResult forceStart(BossId bossId, Player leader) {
        Optional<BossDef> def = definition(bossId);
        if (def.isEmpty()) {
            return StartResult.UNKNOWN_BOSS;
        }
        if (def.get().isWorld()) {
            return StartResult.WRONG_SERVER;
        }
        List<Player> party = partyPlayers(leader);
        Location from = BossItems.loc(leader);
        return startField(def.get(), leader, party, null, true, from);
    }

    @Override
    public boolean forceEnd(UUID encounterId, EncounterState result) {
        Encounter e = byId.get(encounterId);
        if (e == null || !result.ended()) {
            return false;
        }
        if (!e.launched()) {
            finishEarly(e, "boss.admin.ended");
            return true;
        }
        for (Fight.Transition t : e.fight.end(result, "admin")) {
            firePhase(e, t);
        }
        finish(e, result, "admin");
        return true;
    }

    // ------------------------------------------------------------------ 시작

    private List<Player> partyPlayers(Player leader) {
        List<Player> out = new ArrayList<>();
        for (UUID u : parties.ordered(leader.getUniqueId())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            out.add(leader);
        }
        return out;
    }

    /** 소환 미끼 사용 (docs 20 10.4 1단계). 조건: 파티 인원 min~max, 전원 경기장 반경 안, 슬롯 여유, 점검 중 아님. */
    StartResult trySummon(Player leader, BossDef def) {
        if (def.arena().mode() != BossDef.ArenaMode.FIELD) {
            return StartResult.WRONG_SERVER;
        }
        if (!parties.isLeader(leader.getUniqueId())) {
            return StartResult.NOT_LEADER;
        }
        Long until = summonCooldown.get(leader.getUniqueId());
        if (until != null && until > tick) {
            return StartResult.COOLDOWN;
        }
        BossDef.Summon summon = def.summon();
        List<Player> party = partyPlayers(leader);
        return startField(
                def,
                leader,
                party,
                summon == null ? null : summon.item(),
                false,
                BossItems.loc(leader));
    }

    private StartResult startField(
            BossDef def,
            Player leader,
            List<Player> party,
            @Nullable ItemId bait,
            boolean forced,
            Location near) {
        BossBalance bb = balance();
        if (!forced && (party.size() < def.party().min() || party.size() > def.party().max())) {
            return StartResult.PARTY_SIZE;
        }
        if (party.size() > def.party().max()) {
            party = party.subList(0, def.party().max());
        }
        for (Player p : party) {
            if (inEncounter(p.getUniqueId())) {
                return StartResult.BUSY;
            }
        }
        long fieldCount =
                byId.values().stream()
                        .filter(e -> e.def.arena().mode() == BossDef.ArenaMode.FIELD)
                        .count();
        if (fieldCount >= bb.maxFieldEncounters()) {
            return StartResult.NO_SLOT;
        }
        Optional<World> world = items.world(def.arena().world());
        if (world.isEmpty() || !world.get().equals(near.getWorld())) {
            return forced ? StartResult.WRONG_SERVER : StartResult.OUT_OF_RANGE;
        }
        // 가장 가까운 빈 슬롯
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        List<BossDef.Slot> slots = def.arena().slots();
        for (int i = 0; i < slots.size(); i++) {
            if (slotBusy(def.id(), i)) {
                continue;
            }
            Vec3 c = slots.get(i).center();
            double d = near.distanceSquared(new Location(world.get(), c.x(), c.y(), c.z()));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best < 0) {
            return StartResult.NO_SLOT;
        }
        BossDef.Slot slot = slots.get(best);
        Location center =
                new Location(world.get(), slot.center().x(), slot.center().y(), slot.center().z());
        if (!forced) {
            double r2 = slot.radius() * slot.radius();
            for (Player p : party) {
                Location at = BossItems.loc(p);
                if (!world.get().equals(at.getWorld()) || at.distanceSquared(center) > r2) {
                    return StartResult.OUT_OF_RANGE;
                }
            }
        }
        StartResult r =
                create(
                        def,
                        bb,
                        world.get(),
                        center,
                        best,
                        leader.getUniqueId(),
                        party,
                        bait,
                        forced,
                        true);
        if (r == StartResult.STARTED && def.summon() != null && !forced) {
            summonCooldown.put(leader.getUniqueId(), tick + def.summon().cooldownSeconds() * 20L);
        }
        return r;
    }

    /** 인스턴스 서버: 도착한 파티로 레이드 시작 (InstanceManager). */
    public StartResult startInstance(
            BossDef def,
            int slot,
            Location center,
            UUID leader,
            List<Player> party,
            Map<UUID, String> origins) {
        StartResult r =
                create(
                        def,
                        balance(),
                        center.getWorld(),
                        center,
                        slot,
                        leader,
                        party,
                        null,
                        true,
                        true);
        if (r == StartResult.STARTED && !party.isEmpty()) {
            Encounter e = byPlayer.get(party.getFirst().getUniqueId());
            if (e != null) {
                e.origins.putAll(origins);
            }
        }
        return r;
    }

    /** 월드 보스 시작 (WorldBossManager). 준비 단계 없이 바로 등장. */
    public @Nullable Encounter startWorld(BossDef def, UUID eventId, Location center, long seed) {
        BossBalance bb = balance();
        Encounter e =
                new Encounter(
                        this,
                        eventId,
                        def,
                        bb,
                        center.getWorld(),
                        center,
                        -1,
                        new UUID(0, 0),
                        List.of(),
                        Map.of(),
                        seed,
                        null,
                        true);
        BossEncounterStartEvent event = new BossEncounterStartEvent(e.info(), true);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        byId.put(e.id, e);
        e.begin(false);
        return e;
    }

    private StartResult create(
            BossDef def,
            BossBalance bb,
            World world,
            Location center,
            int slot,
            UUID leader,
            List<Player> party,
            @Nullable ItemId bait,
            boolean forced,
            boolean prepare) {
        Map<UUID, BossRole> preset = new HashMap<>();
        for (Player p : party) {
            Optional<BossRole> role = parties.roleOf(p.getUniqueId());
            if (role.isEmpty()) {
                role = api.players().get(p.getUniqueId()).map(h -> h.get(PROFILE).preferred());
            }
            role.ifPresent(r -> preset.put(p.getUniqueId(), r));
        }
        UUID id = UuidV7.next();
        long seed = new java.security.SecureRandom().nextLong();
        Encounter e =
                new Encounter(
                        this, id, def, bb, world, center, slot, leader, party, preset, seed, bait,
                        forced);
        BossEncounterStartEvent event = new BossEncounterStartEvent(e.info(), forced);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return StartResult.CANCELLED;
        }
        byId.put(id, e);
        for (Player p : party) {
            byPlayer.put(p.getUniqueId(), e);
        }
        e.begin(prepare);
        return StartResult.STARTED;
    }

    private boolean slotBusy(BossId boss, int slot) {
        for (Encounter e : byId.values()) {
            if (e.def.id().equals(boss) && e.slot == slot) {
                return true;
            }
        }
        return false;
    }

    /** 인스턴스 칸 사용 중 여부. */
    public boolean instanceSlotBusy(int slot) {
        for (Encounter e : byId.values()) {
            if (e.def.arena().mode() == BossDef.ArenaMode.INSTANCE && e.slot == slot) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Encounter 콜백

    void rememberRole(UUID player, BossRole role) {
        api.players()
                .get(player)
                .ifPresent(h -> h.set(PROFILE, h.get(PROFILE).withPreferred(role)));
    }

    /** boss_encounter RUNNING 행을 비동기로 쓴다. 결과 future는 메인 스레드에서 완료된다. */
    CompletableFuture<Void> recordStart(Encounter e) {
        SeasonClock clock = SeasonServices.clock();
        BossRepository.EncounterRow row =
                new BossRepository.EncounterRow(
                        e.id,
                        e.def.id(),
                        clock.current().value(),
                        clock.week(),
                        serverId(),
                        e.slot,
                        Math.max(1, e.members.size()),
                        e.startedAt(),
                        e.def.isWorld() ? null : e.leader,
                        e.summonItem == null ? null : e.summonItem.toString(),
                        e.seed);
        CompletableFuture<Void> done = new CompletableFuture<>();
        var _ =
                api.database()
                        .asyncVoid(c -> BossRepository.insertEncounter(c, row))
                        .whenCompleteAsync(
                                (v, err) -> {
                                    if (err != null) {
                                        done.completeExceptionally(err);
                                    } else {
                                        done.complete(null);
                                    }
                                },
                                tasks.main());
        return done;
    }

    void firePhase(Encounter e, Fight.Transition t) {
        Bukkit.getPluginManager()
                .callEvent(new BossPhaseChangeEvent(e.info(), t.from(), t.to(), t.cause()));
    }

    void bind(UUID player, Encounter e) {
        byPlayer.put(player, e);
    }

    void unbind(UUID player, Encounter e) {
        byPlayer.remove(player, e);
    }

    void bindClick(Interaction entity, Encounter e, Encounter.ClickKind kind, int index) {
        clicks.put(entity.getUniqueId(), new Click(e, kind, index));
    }

    void unbindClick(Entity entity) {
        clicks.remove(entity.getUniqueId());
    }

    /** 조우 중 경기장 청크에 플러그인 청크 티켓을 건다 (언로드 방지, 10.4). 여러 조우가 같은 청크를 쓰면 센다. */
    void chunkHold(Encounter e, boolean hold) {
        if (hold) {
            Set<Long> keys = new HashSet<>();
            int r = (int) Math.ceil(e.radius / 16.0);
            int cx = e.center.getBlockX() >> 4;
            int cz = e.center.getBlockZ() >> 4;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    long key = Chunk.getChunkKey(x, z) ^ ((long) e.world.getName().hashCode() << 1);
                    keys.add(key);
                    if (chunkHolds.merge(key, 1, Integer::sum) == 1) {
                        e.world.getChunkAt(x, z).addPluginChunkTicket(plugin);
                    }
                }
            }
            encounterChunks.put(e.id, keys);
            return;
        }
        Set<Long> keys = encounterChunks.remove(e.id);
        if (keys == null) {
            return;
        }
        int r = (int) Math.ceil(e.radius / 16.0);
        int cx = e.center.getBlockX() >> 4;
        int cz = e.center.getBlockZ() >> 4;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                long key = Chunk.getChunkKey(x, z) ^ ((long) e.world.getName().hashCode() << 1);
                Integer left = chunkHolds.computeIfPresent(key, (k, v) -> v - 1);
                if (left != null && left <= 0) {
                    chunkHolds.remove(key);
                    e.world.getChunkAt(x, z).removePluginChunkTicket(plugin);
                }
            }
        }
    }

    void worldStage(Encounter e, int stage) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.stage(e.info(), stage, e.effectiveParticipants(), e.fight.hpRatio() * 100);
        }
    }

    void worldUi(Encounter e, Component name, float progress) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.ui(name, progress);
        }
    }

    /** 출발 전에 끝남 (미끼 없음·관리자 종료). 미끼는 차감하지 않았으므로 보상·기록이 없다. */
    void finishEarly(Encounter e, String messageKey) {
        e.broadcast(messageKey);
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
    }

    /**
     * 종료 (docs 20 10.4 6단계): 결과 기록 → BossEncounterEndEvent → 결과 화면 → 보상 트랜잭션 → 리그·Interaction 제거 →
     * 세계 경계 원복.
     */
    void finish(Encounter e, EncounterState result, String cause) {
        if (e.finished()) {
            return;
        }
        List<Contribution> contributions = e.contributions();
        EncounterInfo info = e.info();
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
        long durationMs = e.fight.elapsed() * 50L;
        Instant endedAt = Instant.now();
        boolean releaseOnly = false;
        var fishId = e.def.fish();
        if (fishId != null) {
            releaseOnly =
                    api.content()
                            .snapshot()
                            .get(FishDef.TYPE, fishId.id())
                            .map(FishDef::releaseOnly)
                            .orElse(false);
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new BossEncounterEndEvent(
                                info,
                                result,
                                e.def.type(),
                                contributions,
                                fishId,
                                releaseOnly,
                                durationMs,
                                e.def.story()));
        int season = SeasonServices.clock().current().value();
        int size = contributions.size();
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    BossRepository.endEncounter(
                                            c,
                                            e.id,
                                            result.name(),
                                            endedAt,
                                            durationMs,
                                            Math.max(1, size));
                                    BossRepository.upsertParticipation(
                                            c, e.id, season, contributions);
                                })
                        .exceptionally(
                                err -> {
                                    log.warn(
                                            "조우 종료 기록 실패 {}: {}",
                                            e.id,
                                            String.valueOf(err.getMessage()));
                                    return null;
                                });
        showResult(e, result, contributions);
        playOutro(e, result);
        rewards.settle(e, info, result, contributions, durationMs, endedAt, releaseOnly);
        if (e.def.isWorld()) {
            WorldHooks hooks = worldHooks;
            if (hooks != null) {
                hooks.ended(info, result);
            }
        }
        if (e.def.arena().mode() == BossDef.ArenaMode.INSTANCE) {
            InstanceHooks hooks = instanceHooks;
            if (hooks != null) {
                hooks.ended(e.id, e.origins, info.members());
            }
        }
        log.info(
                "보스 조우 종료 {} {} {} ({}ms, {}명, {})",
                e.def.id(),
                e.id,
                result,
                durationMs,
                size,
                cause);
    }

    /** 클리어 컷씬 (presentation.outro, NPC 모듈이 있을 때만). */
    private void playOutro(Encounter e, EncounterState result) {
        var outro = e.def.presentation().outro();
        if (outro == null || result != EncounterState.CLEARED) {
            return;
        }
        api.service(wiki.creeper.harbor.npc.api.CutsceneService.class)
                .filter(c -> c.exists(outro))
                .ifPresent(
                        c -> {
                            List<Player> players = new ArrayList<>();
                            for (Member m : e.memberList()) {
                                Player p = Bukkit.getPlayer(m.uuid);
                                if (p != null && !m.dropped) {
                                    players.add(p);
                                }
                            }
                            var _ = c.playGroup(players, outro);
                        });
    }

    /** 결과 화면 Dialog (순위·기여도). 전리품은 지급될 때 채팅으로 따로 알린다. */
    private void showResult(Encounter e, EncounterState result, List<Contribution> contributions) {
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "boss.result." + result.key(),
                                Placeholder.component("boss", e.bossName()),
                                Placeholder.unparsed("time", formatMs(e.fight.elapsed() * 50L))));
        int shown = 0;
        Map<UUID, String> names = new HashMap<>();
        for (Member m : e.memberList()) {
            names.put(m.uuid, m.name);
        }
        for (Contribution c : contributions) {
            if (shown++ >= 10) {
                break;
            }
            body.add(
                    api.messages()
                            .get(
                                    "boss.result.line",
                                    Placeholder.unparsed("rank", String.valueOf(c.rank())),
                                    Placeholder.unparsed(
                                            "player", names.getOrDefault(c.player(), "?")),
                                    Placeholder.component(
                                            "role",
                                            api.messages().get("boss.role." + c.role().key())),
                                    Placeholder.unparsed(
                                            "score", String.valueOf(Math.round(c.score() * 100))),
                                    Placeholder.unparsed(
                                            "damage", String.valueOf(Math.round(c.damage()))),
                                    Placeholder.unparsed(
                                            "tension", String.valueOf(Math.round(c.tension()))),
                                    Placeholder.unparsed(
                                            "repair", String.valueOf(Math.round(c.repair()))),
                                    Placeholder.unparsed(
                                            "evasion", String.valueOf(Math.round(c.evasion()))),
                                    Placeholder.unparsed("top", c.top() ? "★" : "")));
        }
        Component title =
                api.messages()
                        .get("boss.result.title", Placeholder.component("boss", e.bossName()));
        List<Player> viewers = e.def.isWorld() ? e.viewers() : e.onlinePlayers();
        for (Player p : viewers) {
            if (e.def.isWorld() && !e.members.containsKey(p.getUniqueId())) {
                continue;
            }
            api.dialogs().notice(p, title, body);
        }
    }

    static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    // ------------------------------------------------------------------ 틱

    private void tick() {
        tick++;
        for (Encounter e : List.copyOf(byId.values())) {
            try {
                e.tick(tick);
            } catch (RuntimeException ex) {
                log.error("보스 조우 틱 오류 {} — 조우를 중단합니다", e.id, ex);
                if (!e.finished()) {
                    for (Fight.Transition t : e.fight.end(EncounterState.ABORTED, "error")) {
                        firePhase(e, t);
                    }
                    finish(e, EncounterState.ABORTED, "error");
                }
            }
        }
        trackHarpoons();
        if (tick % 1200 == 0) {
            parties.purgeInvites(System.currentTimeMillis());
            summonCooldown.values().removeIf(t -> t < tick);
        }
    }

    // ------------------------------------------------------------------ 작살 궤적 판정

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)
                || !(trident.getShooter() instanceof Player player)) {
            return;
        }
        Optional<ItemDef> harpoon = items.harpoon(trident.getItemStack());
        if (harpoon.isEmpty()) {
            return;
        }
        Encounter e = byPlayer.get(player.getUniqueId());
        if (e == null) {
            e = worldEncounterAt(BossItems.loc(player));
        }
        if (e == null || !e.inArena(BossItems.loc(player))) {
            return;
        }
        harpoons.put(
                trident.getUniqueId(),
                new Harpoon(
                        player.getUniqueId(),
                        harpoon.get(),
                        trident.getItemStack().clone(),
                        BossItems.loc(trident)));
    }

    private @Nullable Encounter worldEncounterAt(Location loc) {
        for (Encounter e : byId.values()) {
            if (e.def.isWorld() && e.inArena(loc)) {
                return e;
            }
        }
        return null;
    }

    /** 투사체는 Interaction과 충돌하지 않으므로 매 틱 직전 위치 → 현재 위치 선분과 판정 상자(AABB)의 교차를 직접 계산한다. */
    private void trackHarpoons() {
        if (harpoons.isEmpty()) {
            return;
        }
        int maxAge = balance().harpoonTrackTicks();
        Iterator<Map.Entry<UUID, Harpoon>> it = harpoons.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Harpoon> entry = it.next();
            Harpoon h = entry.getValue();
            Entity entity = Bukkit.getEntity(entry.getKey());
            Player thrower = Bukkit.getPlayer(h.thrower);
            if (!(entity instanceof Trident trident)
                    || !trident.isValid()
                    || thrower == null
                    || ++h.age > maxAge) {
                it.remove();
                continue;
            }
            Location now = BossItems.loc(trident);
            Encounter e = byPlayer.get(h.thrower);
            if (e == null) {
                e = worldEncounterAt(now);
            }
            if (e == null || !now.getWorld().equals(e.world)) {
                it.remove();
                continue;
            }
            double bestT = Double.MAX_VALUE;
            String bestBone = null;
            for (Map.Entry<Interaction, String> box : e.hitboxes()) {
                Interaction i = box.getKey();
                Location bl = BossItems.loc(i);
                Geometry.Aabb aabb =
                        Geometry.Aabb.interaction(
                                        bl.getX(),
                                        bl.getY(),
                                        bl.getZ(),
                                        i.getInteractionWidth(),
                                        i.getInteractionHeight())
                                .grow(0.15);
                double t =
                        aabb.intersect(
                                h.prev.getX(),
                                h.prev.getY(),
                                h.prev.getZ(),
                                now.getX(),
                                now.getY(),
                                now.getZ());
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    bestBone = box.getValue();
                }
            }
            h.prev = now;
            if (bestBone == null) {
                if (trident.isInBlock()) {
                    it.remove();
                }
                continue;
            }
            it.remove();
            trident.remove();
            if (BossItems.stat(h.def, BossItems.STAT_RECOVER, 0) > 0) {
                Map<Integer, ItemStack> left = thrower.getInventory().addItem(h.stack);
                left.values().forEach(s -> thrower.getWorld().dropItem(BossItems.loc(thrower), s));
            }
            e.harpoonHit(thrower, h.def, bestBone);
        }
    }

    // ------------------------------------------------------------------ 리스너

    /** 소환 미끼 사용 (우클릭) · 수리 지점이 없는 갑판의 수리 키트. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack hand = event.getItem();
        Optional<ItemId> id = items.idOf(hand);
        if (id.isEmpty()) {
            return;
        }
        Encounter current = byPlayer.get(p.getUniqueId());
        if (current != null && items.isRepairKit(hand)) {
            if (current.freeRepair(p)) {
                event.setCancelled(true);
            }
            return;
        }
        for (BossDef def : definitions()) {
            BossDef.Summon summon = def.summon();
            if (summon == null || !summon.item().equals(id.get())) {
                continue;
            }
            event.setCancelled(true);
            StartResult r = trySummon(p, def);
            if (r != StartResult.STARTED) {
                api.messages().send(p, "boss.start." + r.name().toLowerCase(java.util.Locale.ROOT));
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Click c = clicks.get(event.getRightClicked().getUniqueId());
        if (c == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true);
        c.encounter().click(event.getPlayer(), c.kind(), c.index());
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Encounter e = byPlayer.get(event.getPlayer().getUniqueId());
        if (e == null) {
            return;
        }
        var in = event.getInput();
        e.input(
                event.getPlayer(),
                new Qte.InputState(
                        in.isForward(),
                        in.isBackward(),
                        in.isLeft(),
                        in.isRight(),
                        in.isJump(),
                        in.isSneak()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Encounter e = byPlayer.get(event.getPlayer().getUniqueId());
        if (e == null) {
            return;
        }
        items.defOf(event.getItem())
                .filter(d -> d.type() == ItemDef.Type.FOOD)
                .ifPresent(d -> e.supply(event.getPlayer()));
    }

    /** 경기장 안에서는 사망 대신 '낙수'. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        Encounter e = byPlayer.get(p.getUniqueId());
        if (e == null || !e.fight.state().fighting()) {
            return;
        }
        if (event.getFinalDamage() >= p.getHealth()) {
            event.setCancelled(true);
            e.member(p.getUniqueId()).ifPresent(m -> e.fall(m, p));
        }
    }

    /** 다른 이유로 죽어도 아이템은 보존한다 (보스전 중). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Encounter e = byPlayer.get(event.getPlayer().getUniqueId());
        if (e != null && !e.finished()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Encounter e = byPlayer.get(event.getPlayer().getUniqueId());
        if (e == null || e.def.isWorld() || !e.fight.state().fighting()) {
            return;
        }
        Location to = event.getTo();
        if (e.inArena(to)) {
            return;
        }
        var cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            event.setCancelled(true);
            api.messages().send(event.getPlayer(), "boss.leave.blocked");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Encounter e = byPlayer.get(uuid);
        if (e != null) {
            e.quit(event.getPlayer());
            InstanceHooks hooks = instanceHooks;
            if (hooks != null && e.def.arena().mode() == BossDef.ArenaMode.INSTANCE) {
                hooks.quit(uuid, e.id);
            }
        }
        var _ =
                tasks.later(
                        balance().slotHoldTicks() + 20L,
                        () -> {
                            if (Bukkit.getPlayer(uuid) == null && !inEncounter(uuid)) {
                                parties.dropOffline(uuid);
                            }
                        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Encounter e = byPlayer.get(p.getUniqueId());
        if (e != null && !e.finished()) {
            var _ =
                    tasks.later(
                            5,
                            () -> {
                                if (p.isOnline()) {
                                    e.rejoin(p);
                                }
                            });
        } else {
            returnFromInstance(p);
        }
        var _ =
                tasks.later(
                        40,
                        () -> {
                            if (p.isOnline()) {
                                rewards.deliverPending(p, false);
                            }
                        });
    }

    /** 인스턴스에서 돌아오면 떠나기 전 위치로 (BossProfile). */
    private void returnFromInstance(Player p) {
        Optional<BossProfile> profile = api.players().get(p.getUniqueId()).map(h -> h.get(PROFILE));
        if (profile.isEmpty()) {
            return;
        }
        BossProfile.ReturnPoint point = profile.get().returnPoint();
        if (point == null || !point.server().equals(serverId())) {
            return;
        }
        items.world(point.world())
                .ifPresent(
                        w -> {
                            var _ =
                                    p.teleportAsync(
                                            new Location(
                                                    w,
                                                    point.x(),
                                                    point.y(),
                                                    point.z(),
                                                    point.yaw(),
                                                    point.pitch()));
                        });
        BossProfile cleared = profile.get().withReturn(null);
        api.players().get(p.getUniqueId()).ifPresent(h -> h.set(PROFILE, cleared));
        UUID uuid = p.getUniqueId();
        var season = api.players().get(p).season();
        var _ = api.database().asyncVoid(c -> BossRepository.saveProfile(c, uuid, season, cleared));
    }

    /** 새 콘텐츠: 진행 중 조우는 시작 시점 정의를 그대로 쓰므로 할 일이 없다 (새 조우부터 새 정의). */
    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        if (event.reloadedTypes().contains(BossDef.TYPE.name()) && !byId.isEmpty()) {
            log.info("보스 정의 리로드: 진행 중 조우 {}개는 기존 정의로 끝까지 진행합니다", byId.size());
        }
    }

    // ------------------------------------------------------------------ 시작·종료

    /** 재시작 때 RUNNING 조우를 ABORTED로 닫고 미끼를 pending으로 돌려준다 (10.4 서버 비정상 종료). */
    private void recover() {
        String server = serverId();
        var _ =
                api.database()
                        .async(c -> BossRepository.abortRunning(c, server))
                        .thenAcceptAsync(
                                aborted -> {
                                    if (!aborted.isEmpty()) {
                                        log.warn(
                                                "비정상 종료로 남은 보스 조우 {}개를 ABORTED로 닫았습니다",
                                                aborted.size());
                                    }
                                    for (BossRepository.Aborted a : aborted) {
                                        rewards.refundBait(a);
                                    }
                                },
                                tasks.main())
                        .exceptionally(
                                err -> {
                                    log.warn("보스 조우 복구 실패: {}", String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    /** 서버 종료 → ABORTED (미끼는 pending으로 돌려준다). 종료 중이라 DB를 바로 쓴다. */
    public void shutdown() {
        for (Encounter e : List.copyOf(byId.values())) {
            if (e.def.isWorld()) {
                // 월드 보스는 resume을 위해 RUNNING으로 남긴다
                e.cleanup();
                continue;
            }
            e.fight.end(EncounterState.ABORTED, "shutdown");
            e.cleanup();
            try {
                var _ =
                        api.database()
                                .asyncVoid(
                                        c -> {
                                            BossRepository.endEncounter(
                                                    c,
                                                    e.id,
                                                    "ABORTED",
                                                    Instant.now(),
                                                    e.fight.elapsed() * 50L,
                                                    Math.max(1, e.members.size()));
                                            if (e.launched() && e.summonItem != null) {
                                                rewards.refundInTx(c, e.id, e.leader, e.summonItem);
                                            }
                                        })
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException ex) {
                log.warn("종료 중 조우 기록 실패 {}: {}", e.id, String.valueOf(ex.getMessage()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byId.clear();
        byPlayer.clear();
        harpoons.clear();
    }

    /** 준비 단계 역할 선택 (/boss role). 준비 중인 조우가 없으면 false. */
    public boolean chooseRole(UUID player, BossRole role) {
        Encounter e = byPlayer.get(player);
        return e != null && e.chooseRole(player, role);
    }

    /** 이동 차단 (HandoffBlocker "encounter"). */
    public boolean blocksHandoff(Player p) {
        Encounter e = byPlayer.get(p.getUniqueId());
        if (e == null || e.finished()) {
            return false;
        }
        return e.member(p.getUniqueId()).map(m -> !m.dropped).orElse(false);
    }

    /** /harbor boss debug. */
    public List<String> debug(UUID encounterId) {
        Encounter e = byId.get(encounterId);
        if (e == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(e.def.id() + " " + e.fight.state() + " slot=" + e.slot + " seed=" + e.seed);
        out.add(
                String.format(
                        "hp=%.1f/%.1f hull=%d/%d hook=%.2f elapsed=%ds",
                        e.fight.hp(),
                        e.fight.maxHp(),
                        e.fight.hull(),
                        e.fight.hullMax(),
                        e.fight.hookGauge(),
                        e.fight.elapsed() / 20));
        out.add("hitboxes=" + e.hitboxes().size() + " telegraph entities=" + e.telegraphEntities());
        for (Map.Entry<Interaction, String> box : e.hitboxes()) {
            Location l = BossItems.loc(box.getKey());
            out.add(
                    String.format(
                            " - %s @ %.1f %.1f %.1f (%.1f×%.1f)",
                            box.getValue(),
                            l.getX(),
                            l.getY(),
                            l.getZ(),
                            box.getKey().getInteractionWidth(),
                            box.getKey().getInteractionHeight()));
            l.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, l, 20, 0.3, 0.3, 0.3, 0);
        }
        for (Member m : e.memberList()) {
            out.add(
                    String.format(
                            " * %s %s dmg=%.0f ten=%.0fs rep=%.0f eva=%.0f %s",
                            m.name,
                            m.role.key(),
                            m.damage,
                            m.tensionTicks / 20.0,
                            m.repair,
                            m.evasion,
                            m.dropped ? "dropped" : m.present() ? "present" : "absent"));
        }
        return out;
    }
}
