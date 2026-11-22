package wiki.creeper.harbor.boss.instance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossProfile;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.encounter.BossItems;
import wiki.creeper.harbor.boss.encounter.EncounterManager;
import wiki.creeper.harbor.boss.logic.UuidV7;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Raid instance assignment and lifecycle (docs 20 10.4 raid instance transfer, harbor:instance
 * proposed).
 *
 * <ol>
 *   <li>Main server party leader requests entry → SlotRequest{boss, party, requestId} on
 *       harbor:instance
 *   <li>An instance server with a free cell reserves it under the distributed lock
 *       harbor:lock:instance:&lt;server&gt; and sends SlotOffer. With no response, "no room" after
 *       10 seconds
 *   <li>The requesting server sends SlotConfirm for the first response (other servers release their
 *       reservations), saves party members' previous locations to BossProfile, then calls
 *       ProxyBridge.requestInstance(party, server, requestId)
 *   <li>The instance server starts when all reserved party members arrive or 60 seconds pass, and
 *       sends them back to their original servers when done
 * </ol>
 *
 * Rejoin: quitting during an instance encounter sets harbor:boss:rejoin:&lt;uuid&gt; = server ID
 * (expires after 120 seconds). The Proxy reads it and sends the player to the same instance. Arenas
 * use cells of the same arena prebuilt in the same world 1,000 blocks apart (if the definition has
 * only one slot, cells are offset by spacing along the X axis).
 */
public final class InstanceManager implements Listener, EncounterManager.InstanceHooks {

    public static final String CHANNEL_NAME = "harbor:instance";
    public static final String REJOIN_PREFIX = "harbor:boss:rejoin:";

    /** Entry request (main server → instance servers). */
    public record SlotRequest(
            String requestId, String boss, List<UUID> party, UUID leader, String origin) {}

    /** Cell reservation response. */
    public record SlotOffer(String requestId, String server, int slot) {}

    /**
     * Confirmation from the requesting server (servers other than server release reservations with
     * the same requestId).
     */
    public record SlotConfirm(String requestId, String server) {}

    static final Channel<SlotRequest> REQUEST = new Channel<>(CHANNEL_NAME, SlotRequest.class);
    static final Channel<SlotOffer> OFFER = new Channel<>(CHANNEL_NAME, SlotOffer.class);
    static final Channel<SlotConfirm> CONFIRM = new Channel<>(CHANNEL_NAME, SlotConfirm.class);

    /** Result. */
    public enum EnterResult {
        REQUESTED,
        UNKNOWN_BOSS,
        NOT_LEADER,
        PARTY_SIZE,
        PENDING,
        BUSY
    }

    /** Pending request on the requesting server. */
    private static final class Outgoing {
        final List<UUID> party;
        final long deadline;
        boolean confirmed;

        Outgoing(List<UUID> party, long deadline) {
            this.party = party;
            this.deadline = deadline;
        }
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final EncounterManager encounters;
    private final PartyManager parties;
    private final BossItems items;
    private final Map<String, Outgoing> outgoing = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private long tick;

    public InstanceManager(
            HarborApi api,
            Tasks tasks,
            Logger log,
            EncounterManager encounters,
            PartyManager parties) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.encounters = encounters;
        this.parties = parties;
        this.items = new BossItems(api);
    }

    public void start() {
        api.bus().subscribe(OFFER, env -> tasks.sync(() -> onOffer(env.payload())));
        if (isInstanceServer()) {
            api.bus().subscribe(REQUEST, env -> tasks.sync(() -> onRequest(env.payload())));
            api.bus().subscribe(CONFIRM, env -> tasks.sync(() -> onConfirm(env.payload())));
        }
        var _ = tasks.timer(20, 20, this::second);
    }

    private boolean isInstanceServer() {
        return api.infra().role() == ServerRole.INSTANCE;
    }

    private BossBalance bb() {
        return encounters.balance();
    }

    // ------------------------------------------------------------- Requesting server (main server)

    /** Party leader requests raid entry (/boss enter, NPC dialog action boss:enter). */
    public EnterResult requestEntry(Player leader, BossId bossId) {
        Optional<BossDef> def =
                encounters
                        .definition(bossId)
                        .filter(d -> d.arena().mode() == BossDef.ArenaMode.INSTANCE);
        if (def.isEmpty()) {
            return EnterResult.UNKNOWN_BOSS;
        }
        UUID uuid = leader.getUniqueId();
        if (!parties.isLeader(uuid)) {
            return EnterResult.NOT_LEADER;
        }
        List<UUID> party = new ArrayList<>();
        for (UUID u : parties.ordered(uuid)) {
            if (Bukkit.getPlayer(u) != null) {
                party.add(u);
            }
        }
        if (party.size() < def.get().party().min() || party.size() > def.get().party().max()) {
            return EnterResult.PARTY_SIZE;
        }
        for (UUID u : party) {
            if (encounters.inEncounter(u)) {
                return EnterResult.BUSY;
            }
        }
        for (Outgoing o : outgoing.values()) {
            if (o.party.contains(uuid)) {
                return EnterResult.PENDING;
            }
        }
        String requestId = UuidV7.next().toString();
        outgoing.put(
                requestId, new Outgoing(List.copyOf(party), tick + bb().instanceReplyTicks() / 20));
        api.bus()
                .publish(
                        REQUEST,
                        new SlotRequest(
                                requestId,
                                bossId.toString(),
                                List.copyOf(party),
                                uuid,
                                api.infra().serverId()));
        for (UUID u : party) {
            send(u, "boss.instance.searching");
        }
        return EnterResult.REQUESTED;
    }

    private void onOffer(SlotOffer offer) {
        Outgoing o = outgoing.get(offer.requestId());
        if (o == null || o.confirmed) {
            return;
        }
        o.confirmed = true;
        outgoing.remove(offer.requestId());
        api.bus().publish(CONFIRM, new SlotConfirm(offer.requestId(), offer.server()));
        // Save the location before leaving (where to return), move after the save finishes
        List<UUID> party = new ArrayList<>();
        for (UUID u : o.party) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) {
                continue;
            }
            party.add(u);
            Location at = BossItems.loc(p);
            BossProfile.ReturnPoint point =
                    new BossProfile.ReturnPoint(
                            api.infra().serverId(),
                            items.worldId(at.getWorld()),
                            at.getX(),
                            at.getY(),
                            at.getZ(),
                            at.getYaw(),
                            at.getPitch());
            HarborPlayer hp = api.players().get(p);
            hp.set(EncounterManager.PROFILE, hp.get(EncounterManager.PROFILE).withReturn(point));
        }
        if (party.isEmpty()) {
            return;
        }
        var season = api.players().get(party.getFirst()).map(HarborPlayer::season).orElse(null);
        Map<UUID, BossProfile> profiles = new HashMap<>();
        for (UUID u : party) {
            api.players().get(u).ifPresent(h -> profiles.put(u, h.get(EncounterManager.PROFILE)));
        }
        if (season == null) {
            return;
        }
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    for (Map.Entry<UUID, BossProfile> e : profiles.entrySet()) {
                                        BossRepository.saveProfile(
                                                c, e.getKey(), season, e.getValue());
                                    }
                                })
                        .thenComposeAsync(
                                v ->
                                        api.proxy()
                                                .requestInstance(
                                                        party, offer.server(), offer.requestId()),
                                tasks.main())
                        .thenAcceptAsync(
                                r -> {
                                    if (!r.ok()) {
                                        log.warn(
                                                "인스턴스 이동 일부 실패 {}: {}",
                                                offer.requestId(),
                                                r.reason());
                                        for (UUID u : party) {
                                            send(u, "boss.instance.move_failed");
                                        }
                                    }
                                },
                                tasks.main())
                        .exceptionally(
                                err -> {
                                    log.warn(
                                            "인스턴스 이동 요청 실패 {}: {}",
                                            offer.requestId(),
                                            String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    // ------------------------------------------------------------------ Instance server

    private void onRequest(SlotRequest req) {
        Optional<BossDef> def;
        try {
            def = encounters.definition(new BossId(HarborId.parse(req.boss())));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (def.isEmpty() || def.get().arena().mode() != BossDef.ArenaMode.INSTANCE) {
            return;
        }
        if (items.world(def.get().arena().world()).isEmpty()) {
            return;
        }
        String server = api.infra().serverId();
        var _ =
                tasks.supplyAsync(
                                () ->
                                        api.locks()
                                                .tryAcquire(
                                                        "harbor:lock:instance:" + server,
                                                        Duration.ofSeconds(5)))
                        .thenAcceptAsync(
                                handle -> {
                                    if (handle.isEmpty()) {
                                        return;
                                    }
                                    try {
                                        int slot = freeSlot();
                                        if (slot < 0) {
                                            return;
                                        }
                                        reservations.put(
                                                req.requestId(),
                                                new Reservation(
                                                        req.requestId(),
                                                        def.get(),
                                                        slot,
                                                        List.copyOf(req.party()),
                                                        req.leader(),
                                                        req.origin(),
                                                        tick + bb().instanceConfirmTicks() / 20));
                                        api.bus()
                                                .publish(
                                                        OFFER,
                                                        new SlotOffer(
                                                                req.requestId(), server, slot));
                                        publishSlots();
                                    } finally {
                                        var _ = tasks.async(handle.get()::release);
                                    }
                                },
                                tasks.main());
    }

    private int freeSlot() {
        int cap = Math.min(bb().instanceSlots(), bb().maxInstanceEncounters());
        Set<Integer> used = new java.util.HashSet<>();
        reservations.values().forEach(r -> used.add(r.slot));
        for (int i = 0; i < cap; i++) {
            if (!used.contains(i) && !encounters.instanceSlotBusy(i)) {
                return i;
            }
        }
        return -1;
    }

    private void onConfirm(SlotConfirm confirm) {
        Reservation r = reservations.get(confirm.requestId());
        if (r == null) {
            return;
        }
        if (!confirm.server().equals(api.infra().serverId())) {
            reservations.remove(confirm.requestId());
            publishSlots();
            return;
        }
        r.confirmed = true;
        r.deadline = tick + bb().instanceArriveTicks() / 20;
        for (UUID u : r.party) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                arrive(r, p);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        for (Reservation r : reservations.values()) {
            if (r.confirmed && r.party.contains(p.getUniqueId())) {
                var _ =
                        tasks.later(
                                5,
                                () -> {
                                    if (p.isOnline()) {
                                        arrive(r, p);
                                    }
                                });
                return;
            }
        }
    }

    private void arrive(Reservation r, Player p) {
        Optional<Location> center = slotCenter(r.def, r.slot);
        if (center.isEmpty()) {
            return;
        }
        Vec3 spawn = r.def.arena().deck().spawn();
        var _ = p.teleportAsync(center.get().clone().add(spawn.x(), spawn.y(), spawn.z()));
        r.arrived.add(p.getUniqueId());
        send(
                p.getUniqueId(),
                "boss.instance.arrived",
                Placeholder.unparsed("count", String.valueOf(r.arrived.size())),
                Placeholder.unparsed("total", String.valueOf(r.party.size())));
        if (r.arrived.size() >= r.party.size()) {
            begin(r);
        }
    }

    /**
     * Cell center: that cell if the definition has several slots, otherwise offset by spacing along
     * the X axis.
     */
    public Optional<Location> slotCenter(BossDef def, int slot) {
        Optional<World> world = items.world(def.arena().world());
        if (world.isEmpty()) {
            return Optional.empty();
        }
        List<BossDef.Slot> slots = def.arena().slots();
        Vec3 c;
        if (slot < slots.size() && slots.size() > 1) {
            c = slots.get(slot).center();
        } else {
            Vec3 base = slots.getFirst().center();
            c = new Vec3(base.x() + slot * bb().instanceSpacing(), base.y(), base.z());
        }
        return Optional.of(new Location(world.get(), c.x(), c.y(), c.z()));
    }

    private void begin(Reservation r) {
        if (!reservations.remove(r.requestId, r)) {
            return;
        }
        List<Player> players = new ArrayList<>();
        Map<UUID, String> origins = new HashMap<>();
        UUID leader = r.leader;
        for (UUID u : r.party) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && r.arrived.contains(u)) {
                players.add(p);
                origins.put(u, r.origin);
            }
        }
        if (players.isEmpty()) {
            publishSlots();
            return;
        }
        if (!origins.containsKey(leader)) {
            leader = players.getFirst().getUniqueId();
        }
        Optional<Location> center = slotCenter(r.def, r.slot);
        if (center.isEmpty()) {
            return;
        }
        // Party leader first
        UUID first = leader;
        players.sort(
                (a, b) ->
                        a.getUniqueId().equals(first) ? -1 : b.getUniqueId().equals(first) ? 1 : 0);
        var result =
                encounters.startInstance(r.def, r.slot, center.get(), leader, players, origins);
        if (result != wiki.creeper.harbor.boss.api.BossService.StartResult.STARTED) {
            log.warn("인스턴스 레이드 시작 실패 {}: {}", r.requestId, result);
            ended(new UUID(0, 0), origins, origins.keySet());
        }
        publishSlots();
    }

    private void second() {
        tick++;
        outgoing.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().deadline <= tick) {
                                for (UUID u : e.getValue().party) {
                                    send(u, "boss.instance.no_slot");
                                }
                                return true;
                            }
                            return false;
                        });
        Iterator<Reservation> it = reservations.values().iterator();
        List<Reservation> start = new ArrayList<>();
        while (it.hasNext()) {
            Reservation r = it.next();
            if (r.deadline > tick) {
                continue;
            }
            if (r.confirmed) {
                start.add(r);
            } else {
                it.remove();
            }
        }
        start.forEach(this::begin);
    }

    /**
     * Records cell state in the Redis hash harbor:instance:&lt;server&gt; (for administration and
     * observability).
     */
    private void publishSlots() {
        Map<String, String> fields = new HashMap<>();
        reservations
                .values()
                .forEach(r -> fields.put(String.valueOf(r.slot), "reserved|" + r.requestId));
        String key = CHANNEL_NAME + ":" + api.infra().serverId();
        var _ =
                tasks.async(
                        () -> {
                            api.redis().sync().del(key);
                            if (!fields.isEmpty()) {
                                api.redis().sync().hset(key, fields);
                            }
                        });
    }

    // ------------------------------------------------------------------
    // EncounterManager.InstanceHooks

    @Override
    public void ended(UUID encounter, Map<UUID, String> origins, Collection<UUID> members) {
        List<String> keys = new ArrayList<>();
        for (UUID u : members) {
            keys.add(REJOIN_PREFIX + u);
        }
        if (!keys.isEmpty()) {
            var _ = tasks.async(() -> api.redis().sync().del(keys.toArray(String[]::new)));
        }
        for (UUID u : members) {
            send(u, "boss.instance.returning");
        }
        var _ =
                tasks.later(
                        200,
                        () -> {
                            for (UUID u : members) {
                                String origin = origins.get(u);
                                if (origin == null || Bukkit.getPlayer(u) == null) {
                                    continue;
                                }
                                var _ = api.proxy().requestMove(u, origin, "boss_return");
                            }
                        });
    }

    @Override
    public void quit(UUID player, UUID encounter) {
        String server = api.infra().serverId();
        long ttl = bb().rejoinTtlSeconds();
        var _ =
                tasks.async(
                        () ->
                                api.redis()
                                        .sync()
                                        .set(
                                                REJOIN_PREFIX + player,
                                                server,
                                                io.lettuce.core.SetArgs.Builder.ex(ttl)));
    }

    private void send(
            UUID player,
            String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            api.messages().send(p, key, resolvers);
        }
    }

    /** Number of pending reservations (debug). */
    public int reservations() {
        return reservations.size();
    }

    @Nullable String debugLine() {
        return reservations.isEmpty() ? null : reservations.keySet().toString();
    }
}
