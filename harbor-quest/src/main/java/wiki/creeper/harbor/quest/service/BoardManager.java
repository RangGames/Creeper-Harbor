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
 * 어부 길드 게시판 (docs 20 6.4): 일일·주간 의뢰와 수족관 관장 수집 의뢰. 초기화 뒤 처음 열 때 후보를 뽑아 quest_board에 저장하고(시드
 * hash(uuid, cycle)), 다음 초기화까지 못 끝낸 의뢰는 만료한다 (행 삭제, 벌칙 없음). 게시판은 NPC가 아니라 Quest가 소유한 Interaction +
 * Text Display다.
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

    /** 지금 cycle (일일 = 게임 날짜, 주간·수집 = 시즌 주차). */
    public String cycle(QuestType kind) {
        return kind == QuestType.DAILY
                ? Cycles.daily(engine.api.time().gameDay())
                : Cycles.weekly(engine.season(), engine.deps.clock().week());
    }

    Optional<BoardState> current(HarborPlayer hp, QuestType kind) {
        return hp.get(QuestEngine.COMPONENT).board(kind).filter(b -> b.cycle().equals(cycle(kind)));
    }

    /** 이번 cycle 게시판에 올라온 의뢰인지. */
    boolean offered(HarborPlayer hp, QuestDef def) {
        return current(hp, def.type()).map(b -> b.offers().contains(def.id())).orElse(false);
    }

    /** 더 수락할 수 있는지 (일일은 3개 중 1개만, 이미 고른 의뢰는 포기 후 다시 받을 수 있다). */
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

    /** 수락 트랜잭션에 같이 쓸 게시판 갱신. */
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

    /** 이번 cycle 게시판 (없으면 뽑아서 저장). 결과는 메인 스레드. */
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

    /** 후보: 해금한 가장 높은 지역까지, 목표 품질은 지금 상한 이하, 시작 조건 충족. */
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

    /** 초기화(DailyResetEvent·WeeklyResetEvent) 뒤: 지난 cycle의 진행 중인 의뢰를 만료한다 (행 삭제, 벌칙 없음). */
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

    // ------------------------------------------------------------------ 게시판 엔티티

    /**
     * board.yml location에 Interaction + Text Display를 세운다 (저장하지 않는 엔티티, 청크 언로드 뒤 주기 확인으로 다시 세움).
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

    /** 비정상 종료로 남은 게시판 엔티티 정리 (PDC 태그). */
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

    /** 게시판을 눌렀을 때 열 화면 (QuestDialogs가 등록). */
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

    /** 수락할 수 있는 게시판 의뢰 수 (표시용). */
    int remainingPicks(HarborPlayer hp, QuestType kind) {
        return current(hp, kind)
                .map(b -> Math.max(0, engine.boardRules().slot(kind).pick() - b.chosen().size()))
                .orElse(engine.boardRules().slot(kind).pick());
    }
}
