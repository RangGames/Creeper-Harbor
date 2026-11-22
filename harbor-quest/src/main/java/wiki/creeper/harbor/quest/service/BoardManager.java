package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.BoardState;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.BoardRoller;
import wiki.creeper.harbor.quest.logic.Cycles;
import wiki.creeper.harbor.quest.logic.Requirements;

/**
 * Fishermen's guild board (docs 20 6.4): daily and weekly requests plus the aquarium curator's
 * collection requests. On the first open after a reset, candidates are drawn and saved to
 * quest_board (seed hash(uuid, cycle)); requests not finished by the next reset expire (row
 * deleted, no penalty). The board is not an NPC but an Interaction + Text Display owned by Quest.
 */
public final class BoardManager implements Listener {

    private static final Set<QuestType> KINDS =
            Set.of(QuestType.DAILY, QuestType.WEEKLY, QuestType.COLLECT);

    private final QuestEngine engine;
    private final NamespacedKey tag;
    private final List<Entity> spawned = new ArrayList<>();

    BoardManager(QuestEngine engine) {
        this.engine = engine;
        this.tag = new NamespacedKey(engine.plugin, "quest_board");
    }

    /** Current cycle (daily = game date, weekly and collect = season week). */
    public String cycle(QuestType kind) {
        return kind == QuestType.DAILY
                ? Cycles.daily(engine.api.time().gameDay())
                : Cycles.weekly(engine.season(), engine.deps.clock().week());
    }

    Optional<BoardState> current(HarborPlayer hp, QuestType kind) {
        return hp.get(QuestEngine.COMPONENT).board(kind).filter(b -> b.cycle().equals(cycle(kind)));
    }

    /** Whether this request is on this cycle's board. */
    boolean offered(HarborPlayer hp, QuestDef def) {
        return current(hp, def.type()).map(b -> b.offers().contains(def.id())).orElse(false);
    }

    /**
     * Whether more can be accepted (daily allows only 1 of 3; an already chosen request can be
     * taken again after abandoning).
     */
    boolean canChoose(HarborPlayer hp, QuestDef def) {
        Optional<BoardState> board = current(hp, def.type());
        if (board.isEmpty() || !board.get().offers().contains(def.id())) {
            return false;
        }
        if (board.get().chosen().contains(def.id())) {
            return true;
        }
        return board.get().chosen().size() < engine.boardRules().slot(def.type()).pick();
    }

    /** Board update written together in the accept transaction. */
    @Nullable SqlConsumer chooseWrite(HarborPlayer hp, QuestDef def) {
        Optional<BoardState> board = current(hp, def.type());
        if (board.isEmpty()) {
            return null;
        }
        UUID uuid = hp.uuid();
        BoardState next = board.get().withChosen(def.id());
        return c -> QuestRepository.saveBoard(c, uuid, next);
    }

    QuestData markChosen(QuestData data, QuestDef def) {
        return data.board(def.type())
                .filter(b -> b.cycle().equals(cycle(def.type())))
                .map(b -> data.withBoard(b.withChosen(def.id())))
                .orElse(data);
    }

    /** This cycle's board (drawn and saved if absent). Result on the main thread. */
    public CompletableFuture<BoardState> ensure(Player player, QuestType kind, boolean reroll) {
        HarborPlayer hp = engine.api.players().get(player);
        Optional<BoardState> existing = current(hp, kind);
        if (existing.isPresent() && !reroll) {
            return CompletableFuture.completedFuture(existing.get());
        }
        String cycle = cycle(kind);
        String seed = reroll ? cycle + ":" + System.nanoTime() : cycle;
        List<QuestId> offers =
                BoardRoller.pick(
                        hp.uuid(),
                        seed,
                        candidates(hp, kind),
                        engine.boardRules().slot(kind).offers());
        BoardState board = new BoardState(kind, cycle, offers, List.of());
        UUID uuid = hp.uuid();
        return engine.serial(
                        uuid,
                        c -> {
                            QuestRepository.saveBoard(c, uuid, board);
                            return board;
                        })
                .thenApply(
                        b -> {
                            engine.api
                                    .players()
                                    .get(uuid)
                                    .ifPresent(
                                            p ->
                                                    engine.put(
                                                            p,
                                                            p.get(QuestEngine.COMPONENT)
                                                                    .withBoard(b)));
                            return b;
                        });
    }

    /**
     * Candidates: up to the highest unlocked region, objective quality at or below the current cap,
     * start requirements met.
     */
    List<BoardRoller.Candidate> candidates(HarborPlayer hp, QuestType kind) {
        BoardRules rules = engine.boardRules();
        int highest = engine.highestRegion(hp.uuid()).map(rules::rank).orElse(0);
        int cap = engine.deps.jobs().qualityCap(hp.uuid()).stars();
        Requirements.Facts facts = engine.facts(hp);
        List<BoardRoller.Candidate> result = new ArrayList<>();
        for (QuestDef def : engine.defs()) {
            QuestDef.Pool pool = def.pool();
            if (def.type() != kind || pool == null || !engine.inWindow(def)) {
                continue;
            }
            RegionId region = pool.region();
            if (region != null && rules.rank(region) > Math.max(0, highest)) {
                continue;
            }
            boolean overCap = false;
            for (Objective o : def.objectives()) {
                var q = o.qualityMin();
                if (q != null && q.stars() > cap) {
                    overCap = true;
                }
            }
            if (overCap || !Requirements.evaluate(def.requires(), facts).met()) {
                continue;
            }
            result.add(new BoardRoller.Candidate(def.id(), pool.weight()));
        }
        return result;
    }

    /**
     * After a reset (DailyResetEvent, WeeklyResetEvent): expires in-progress requests of past
     * cycles (row deleted, no penalty).
     */
    void expire(Player player, Set<QuestType> kinds) {
        HarborPlayer hp = engine.api.players().get(player);
        UUID uuid = hp.uuid();
        QuestData data = hp.get(QuestEngine.COMPONENT);
        List<QuestEntry> stale = new ArrayList<>();
        for (QuestEntry e : data.entries().values()) {
            if (!e.state().open() && e.state() != QuestState.COMPLETED) {
                continue;
            }
            Optional<QuestDef> def = engine.def(e.quest());
            if (def.isEmpty() || !kinds.contains(def.get().type()) || !e.state().open()) {
                continue;
            }
            if (!e.progress().cycle().equals(engine.cycleOf(def.get()))
                    && !engine.payer().completing(uuid, e.quest())) {
                stale.add(e);
            }
        }
        QuestData next = data;
        for (QuestType kind : kinds) {
            if (next.board(kind).filter(b -> !b.cycle().equals(cycle(kind))).isPresent()) {
                next = next.withoutBoard(kind);
            }
        }
        for (QuestEntry e : stale) {
            next = next.without(e.quest());
            engine.cancelPending(uuid, e.quest());
            var _ =
                    engine.serial(
                            uuid,
                            c -> {
                                QuestRepository.delete(c, uuid, e.season(), e.quest());
                                return true;
                            });
        }
        if (!stale.isEmpty()) {
            engine.api.messages().send(player, "quest.board.expired");
        }
        engine.put(hp, next);
        engine.refreshMarkers(uuid);
    }

    static Set<QuestType> kinds() {
        return KINDS;
    }

    // ------------------------------------------------------------------ Board entities

    /**
     * Places an Interaction + Text Display at board.yml location (non-persistent entities, placed
     * again by a periodic check after chunk unload).
     */
    void ensureEntity() {
        BoardRules.Location loc = engine.boardRules().location();
        spawned.removeIf(e -> !e.isValid());
        if (loc == null || !spawned.isEmpty()) {
            return;
        }
        World world = world(loc.world());
        if (world == null
                || !world.isChunkLoaded(
                        ((int) Math.floor(loc.x())) >> 4, ((int) Math.floor(loc.z())) >> 4)) {
            return;
        }
        Location at = new Location(world, loc.x(), loc.y(), loc.z(), loc.yaw(), 0);
        Interaction hit =
                world.spawn(
                        at,
                        Interaction.class,
                        i -> {
                            i.setPersistent(false);
                            i.setInteractionWidth(1.2f);
                            i.setInteractionHeight(2.0f);
                            i.getPersistentDataContainer()
                                    .set(tag, PersistentDataType.BYTE, (byte) 1);
                        });
        TextDisplay label =
                world.spawn(
                        at.clone().add(0, 2.2, 0),
                        TextDisplay.class,
                        t -> {
                            t.setPersistent(false);
                            t.text(engine.api.messages().get("quest.board.label"));
                            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                            t.getPersistentDataContainer()
                                    .set(tag, PersistentDataType.BYTE, (byte) 1);
                        });
        spawned.add(hit);
        spawned.add(label);
    }

    /** Cleans up board entities left by an abnormal shutdown (PDC tag). */
    void cleanup() {
        spawned.forEach(Entity::remove);
        spawned.clear();
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }

    private @Nullable World world(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return direct;
        }
        Balance balance = engine.balance();
        for (String alias : balance.keys("world_alias")) {
            if (balance.string("world_alias." + alias).orElse("").equals(contentWorld)) {
                World w = Bukkit.getWorld(alias);
                if (w != null) {
                    return w;
                }
            }
        }
        return null;
    }

    private java.util.function.@Nullable Consumer<Player> openBoard;

    /** Screen opened when the board is clicked (registered by QuestDialogs). */
    void setOpener(java.util.function.Consumer<Player> open) {
        this.openBoard = open;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !event.getRightClicked()
                        .getPersistentDataContainer()
                        .has(tag, PersistentDataType.BYTE)) {
            return;
        }
        event.setCancelled(true);
        var open = openBoard;
        if (open != null) {
            open.accept(event.getPlayer());
        }
    }

    /** Number of board requests that can be accepted (for display). */
    int remainingPicks(HarborPlayer hp, QuestType kind) {
        return current(hp, kind)
                .map(b -> Math.max(0, engine.boardRules().slot(kind).pick() - b.chosen().size()))
                .orElse(engine.boardRules().slot(kind).pick());
    }
}
