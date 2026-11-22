package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.content.BossType;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.fishing.api.TensionSession;

/**
 * One running encounter (docs 20 10.4 encounter flow). Uses the definition snapshot from its start
 * to the end (reloads do not change it). Phase rules live in the pure logic {@link Fight}; this
 * class handles preparation, departure, per-tick progress, state transitions and cleanup. Rig, hit
 * detection, pattern telegraphs, anchor tension, QTE and UI are driven through the server API by
 * collaborators in the same package ({@link BossRig}, {@link HarpoonCombat}, {@link
 * PatternTelegraphs}, {@link AnchorLines}, {@link FinalTugQte}, {@link DeckStations}, {@link
 * EncounterRoster}, {@link WorldBossTracker}, {@link EncounterHud}). Main thread only.
 */
public final class Encounter {

    /** Modifier key: boss damage (Core modifier pipeline, skill tree and buffs). */
    static final StatKey BOSS_DAMAGE = StatKey.of("boss_damage");

    /** Click target (helm, damaged spot). */
    enum ClickKind {
        WHEEL,
        REPAIR
    }

    public final UUID id;
    public final BossDef def;
    final BossBalance bb;
    final EncounterManager mgr;
    final World world;

    /** Slot center (boundary center). */
    final Location center;

    final int slot;
    final double radius;
    final UUID leader;
    final @Nullable ItemId summonItem;
    final boolean forced;
    public final long seed;
    final SplittableRandom random;
    public final Fight fight;
    final Instant createdAt = Instant.now();

    /** For instance encounters, the server to return to (party members by original server). */
    final Map<UUID, String> origins = new HashMap<>();

    /** Participants (role selection order = join order). */
    final LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();

    // Collaborators (the constructor only stores references)
    final EncounterHud hud = new EncounterHud(this);
    final BossRig bossRig = new BossRig(this);
    final DeckStations stations = new DeckStations(this);
    final AnchorLines anchors = new AnchorLines(this);
    final HarpoonCombat combat = new HarpoonCombat(this);
    final PatternTelegraphs patterns = new PatternTelegraphs(this);
    final FinalTugQte qte = new FinalTugQte(this);
    final WorldBossTracker worldBoss = new WorldBossTracker(this);
    final EncounterRoster roster = new EncounterRoster(this);
    final PhaseTransitions transitions = new PhaseTransitions(this);

    private boolean launched;
    boolean launching;

    /**
     * Whether the summon bait was taken from the inventory (decides refunds even if the encounter
     * ends before launched).
     */
    boolean baitTaken;

    private long prepareDeadline;
    private Instant startedAt = Instant.now();
    long anchorGraceUntil = -1;
    boolean latencySlow;

    private EncounterState lastState = EncounterState.PREPARING;
    private boolean finished;

    Encounter(
            EncounterManager mgr,
            UUID id,
            BossDef def,
            BossBalance bb,
            World world,
            Location center,
            int slot,
            UUID leader,
            List<Player> players,
            Map<UUID, BossRole> presetRoles,
            long seed,
            @Nullable ItemId summonItem,
            boolean forced) {
        this.mgr = mgr;
        this.id = id;
        this.def = def;
        this.bb = bb;
        this.world = world;
        this.center = center.clone();
        this.slot = slot;
        this.radius = def.isWorld() ? bb.worldRadius() : def.arena().slots().getFirst().radius();
        this.leader = leader;
        this.summonItem = summonItem;
        this.forced = forced;
        this.seed = seed;
        this.random = new SplittableRandom(seed);
        for (Player p : players) {
            BossRole preset = presetRoles.get(p.getUniqueId());
            Member m =
                    new Member(
                            p.getUniqueId(),
                            p.getName(),
                            preset == null ? recommended(p) : preset,
                            true);
            m.chose = preset != null;
            m.rememberIp(p);
            members.put(p.getUniqueId(), m);
        }
        this.fight = FightSetup.create(def, bb, members.size());
    }

    private BossRole recommended(Player p) {
        return BossRole.recommended(mgr.api().players().get(p).jobLine());
    }

    // ------------------------------------------------------------------ Location

    Location deck(Vec3 v) {
        return center.clone().add(v.x(), v.y(), v.z());
    }

    Location deckSpawn() {
        return deck(def.arena().deck().spawn());
    }

    Location bossLocation() {
        Location boss = deck(def.arena().deck().boss());
        Location target = deckSpawn();
        Vector dir = target.toVector().subtract(boss.toVector());
        if (dir.lengthSquared() > 1e-6) {
            boss.setDirection(dir);
            boss.setPitch(0);
        }
        return boss;
    }

    boolean inArena(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        double dx = loc.getX() - center.getX();
        double dz = loc.getZ() - center.getZ();
        return dx * dx + dz * dz <= (radius + 2) * (radius + 2);
    }

    // ------------------------------------------------------------------ Start

    /**
     * Starts in the preparation stage (role selection Dialog). If prepare is false, departs
     * immediately (world boss, resume).
     */
    void begin(boolean prepare) {
        long now = mgr.now();
        fight.begin(now);
        prepareDeadline = now + (prepare ? bb.prepareTicks() : 0);
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null) {
                continue;
            }
            roster.enter(p, m, true);
            if (prepare && !m.chose) {
                roster.askRole(p);
            }
        }
        if (prepare) {
            broadcast(
                    "boss.prepare.start",
                    Placeholder.component("boss", bossName()),
                    Placeholder.unparsed("seconds", String.valueOf(bb.prepareTicks() / 20)));
        }
    }

    /** Role selection during preparation (Dialog or /boss role). */
    boolean chooseRole(UUID player, BossRole role) {
        return roster.chooseRole(player, role);
    }

    /**
     * Departure: fill the minimum anchors → take the bait → save the summoner immediately → record
     * boss_encounter RUNNING → spawn (10.4 steps 3-4). The boss is not spawned before the save
     * finishes.
     */
    private void launch() {
        launching = true;
        roster.fillAnchors();
        if (summonItem != null) {
            Player summoner = Bukkit.getPlayer(leader);
            if (summoner == null || !mgr.items().takeOne(summoner, summonItem)) {
                mgr.finishEarly(this, "boss.start.no_bait");
                return;
            }
            baitTaken = true;
        }
        startedAt = Instant.now();
        // Save the inventory without the bait first, then record RUNNING. In the reverse order, if
        // the server died after recording, the inventory would roll back to the save that still had
        // the bait and recovery (refundBait) would refund it again, duplicating the bait. Vanilla
        // saveData() does not help because the Harbor save is the source of truth
        CompletableFuture<Void> saved =
                summonItem == null
                        ? CompletableFuture.completedFuture(null)
                        : mgr.api()
                                .players()
                                .saveNow(leader)
                                .exceptionally(
                                        e -> {
                                            mgr.log()
                                                    .warn(
                                                            "소환 미끼 저장 실패 {}: {}",
                                                            id,
                                                            String.valueOf(e.getMessage()));
                                            return null;
                                        });
        var _ =
                saved.thenComposeAsync(v -> mgr.recordStart(this), mgr.tasks().main())
                        .whenCompleteAsync(
                                (ok, err) -> {
                                    if (finished) {
                                        return;
                                    }
                                    if (err != null) {
                                        mgr.log()
                                                .warn(
                                                        "boss_encounter 기록 실패 {}: {}",
                                                        id,
                                                        String.valueOf(err.getMessage()));
                                    }
                                    launched = true;
                                    handle(fight.allReady());
                                },
                                mgr.tasks().main());
    }

    // ------------------------------------------------------------------ Tick

    void tick(long now) {
        if (finished) {
            return;
        }
        if (fight.state() == EncounterState.PREPARING) {
            if (!launching && (now >= prepareDeadline || roster.allChose())) {
                launch();
            }
            return;
        }
        if (!launched) {
            return;
        }
        if (now % 10 == 0) {
            roster.updatePresence(now);
        }
        int active = 0;
        int safe = 0;
        for (Member m : members.values()) {
            TensionSession s = m.session;
            if (s != null && s.isActive()) {
                active++;
                if (anchors.inSafe(s)) {
                    safe++;
                    m.tensionTicks++;
                    m.activity(now);
                    worldBoss.activity(m.uuid, now);
                }
            }
            if (!m.dropped && now - m.lastActivity <= bb.activeWindowTicks()) {
                m.activeTicks++;
            }
            if (m.fallenUntil >= 0 && now >= m.fallenUntil) {
                m.fallenUntil = -1;
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null && m.present()) {
                    var _ = p.teleportAsync(def.isWorld() ? p.getLocation() : deckSpawn());
                    send(m.uuid, "boss.fall.back");
                }
            }
            if (m.reopenAt >= 0 && now >= m.reopenAt) {
                m.reopenAt = -1;
                anchors.open(m);
            }
        }
        boolean grace = anchorGraceUntil >= 0 && now < anchorGraceUntil;
        boolean allAbsent = !def.isWorld() && members.values().stream().noneMatch(Member::present);
        handle(fight.tick(now, new Fight.Inputs(safe, active, grace, allAbsent)));
        if (finished) {
            return;
        }
        switch (fight.phase()) {
            case Fight.Hook _ -> {
                patterns.run(now, BossDef.PhaseKind.HOOK);
                patterns.update(now);
            }
            case Fight.Counter _ -> {
                patterns.run(now, BossDef.PhaseKind.COUNTER);
                patterns.update(now);
                combat.tickWeak(now, safe > 0);
                if (safe > 0) {
                    double perTick = fight.maxHp() * bb.anchorReelDamagePerSecond() / 20.0;
                    List<Fight.Transition> out = new ArrayList<>();
                    fight.damage(perTick * safe, out);
                    anchors.mirror();
                    handle(out);
                }
            }
            case Fight.FinalTug _ -> qte.tick(now);
            default -> {
                // Preparation, intro, end
            }
        }
        if (def.isWorld()) {
            worldBoss.tick(now);
        }
        if (now % 5 == 0) {
            hud.update(now);
        }
    }

    // ------------------------------------------------------------------ State transitions

    void handle(List<Fight.Transition> transitions) {
        for (Fight.Transition t : transitions) {
            lastState = t.to();
            this.transitions.onTransition(t);
            if (finished) {
                return;
            }
        }
    }

    // ------------------------------------------ Input and interaction (delegated to collaborators)

    /** Harpoon throw hit on a rig part (trajectory hit detection in EncounterManager). */
    void harpoonHit(Player p, ItemDef harpoon, String bone) {
        combat.harpoonHit(p, harpoon, bone);
    }

    /** Right-click on the helm or a damaged spot. */
    void click(Player p, ClickKind kind, int index) {
        stations.click(p, kind, index);
    }

    /** Deck without repair spots: right-click with a repair kit in hand. */
    boolean freeRepair(Player p) {
        return stations.freeRepair(p);
    }

    /** Quartermaster ate buff food. */
    void supply(Player p) {
        stations.supply(p);
    }

    /** Drop bonus when there is a quartermaster (proposed). */
    boolean hasQuartermaster() {
        return stations.hasQuartermaster();
    }

    /** Movement input (QTE, captain). Only newly pressed keys are passed on. */
    void input(Player p, Qte.InputState state) {
        Member m = members.get(p.getUniqueId());
        if (m == null) {
            return;
        }
        long now = mgr.now();
        var pressed = state.newlyPressed(m.input);
        m.input = state;
        if (pressed.isEmpty()) {
            return;
        }
        if (m.uuid.equals(stations.helm())) {
            patterns.steer(p, m, pressed);
        }
        qte.press(p, m, pressed, now);
    }

    /** Rejoin (within the slot hold time). field encounters send the player back to the deck. */
    void rejoin(Player p) {
        roster.rejoin(p);
    }

    void quit(Player p) {
        roster.quit(p);
    }

    /**
     * 'Falling into the water' instead of dying in the arena (items kept, back on deck after 5s).
     */
    void fall(Member m, Player p) {
        roster.fall(m, p);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    Optional<Member> member(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    // ------------------------------------------------------------------ World boss

    public int effectiveParticipants() {
        return worldBoss.effectiveParticipants();
    }

    /** 25% stage (0 start, 1 = 75% or less, 2 = 50% or less, 3 = 25% or less). */
    public int stage() {
        return worldBoss.stage();
    }

    /** resume: restores from saved progress, contributions and active time (ticks). */
    public void restore(
            double hpRatio, Map<UUID, Contribution> saved, Map<UUID, Long> activeTicks) {
        worldBoss.restore(hpRatio, saved, activeTicks);
    }

    /**
     * Active time per participant (ticks). Saved for world boss resume (kept separately since
     * Contribution does not have it).
     */
    public Map<UUID, Long> activeTicks() {
        Map<UUID, Long> out = new HashMap<>();
        for (Member m : members.values()) {
            out.put(m.uuid, m.activeTicks);
        }
        return out;
    }

    // ------------------------------------------------------------------ End

    /**
     * Removes the rig, Interactions, telegraphs, boss bars, tension, boundary and chunk tickets.
     */
    void cleanup() {
        finished = true;
        patterns.clear();
        bossRig.remove();
        stations.remove();
        for (Member m : members.values()) {
            anchors.close(m);
            m.track = null;
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null) {
                roster.leaveVisuals(p, m);
            }
        }
        mgr.chunkHold(this, false);
    }

    /** Contribution ranking (ContributionCalc). */
    public List<Contribution> contributions() {
        return EncounterSummary.contributions(this);
    }

    /** Whether everyone took part from the start (fastest record candidate). */
    boolean allFromStart() {
        return members.values().stream().allMatch(m -> m.fromStart);
    }

    public EncounterInfo info() {
        return EncounterSummary.info(this);
    }

    EncounterState lastState() {
        return lastState;
    }

    Instant startedAt() {
        return startedAt;
    }

    boolean launched() {
        return launched;
    }

    public boolean finished() {
        return finished;
    }

    @Nullable RigInstance rig() {
        return bossRig.instance();
    }

    /** Rig hitbox Interactions and bone names (for harpoon trajectory hits). */
    List<Map.Entry<Interaction, String>> hitboxes() {
        return bossRig.hitboxes();
    }

    int telegraphEntities() {
        return patterns.entityCount();
    }

    /**
     * Who sees markers, the rig and Interactions (party members; for world bosses, players within
     * the radius).
     */
    List<Player> viewers() {
        if (!def.isWorld()) {
            return onlinePlayers();
        }
        List<Player> out = new ArrayList<>();
        double r2 = bb.worldRadius() * bb.worldRadius();
        for (Player p : world.getPlayers()) {
            if (BossItems.loc(p).distanceSquared(center) <= r2) {
                out.add(p);
            }
        }
        return out;
    }

    List<Player> onlinePlayers() {
        List<Player> out = new ArrayList<>();
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null && !m.dropped) {
                out.add(p);
            }
        }
        return out;
    }

    Collection<Member> memberList() {
        return members.values();
    }

    // ------------------------------------------------------------------ Messages (EncounterHud)

    public Component bossName() {
        return hud.bossName();
    }

    Component msg(String key, TagResolver... resolvers) {
        return hud.msg(key, resolvers);
    }

    void send(UUID player, String key, TagResolver... resolvers) {
        hud.send(player, key, resolvers);
    }

    void broadcast(String key, TagResolver... resolvers) {
        hud.broadcast(key, resolvers);
    }

    BossType type() {
        return def.type();
    }
}
