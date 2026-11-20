package wiki.creeper.harbor.quest.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.BoardState;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;

/** Quest 서비스 묶음 (엔진·보상·게시판·추적·연대기·서버 진행도)과 UI·명령이 쓰는 창구. */
public final class QuestServices {

    private final QuestEngine engine;
    private final RewardPayer payer;
    private final BoardManager board;
    private final Tracker tracker;
    private final TrackerBar trackerBar;
    private final ChronicleManager chronicle;
    private final ServerProgressManager progress;

    private QuestServices(QuestEngine engine) {
        this.engine = engine;
        this.payer = new RewardPayer(engine);
        this.board = new BoardManager(engine);
        this.tracker = new Tracker(engine);
        this.trackerBar = new TrackerBar(engine);
        this.chronicle = new ChronicleManager(engine);
        this.progress = new ServerProgressManager(engine);
        engine.attach(payer, board, tracker, chronicle);
    }

    public static QuestServices create(Plugin plugin, HarborApi api, Tasks tasks, Logger log) {
        return new QuestServices(new QuestEngine(plugin, api, tasks, log, Deps.resolve()));
    }

    public QuestEngine engine() {
        return engine;
    }

    public ChronicleManager chronicle() {
        return chronicle;
    }

    public ServerProgressManager serverProgress() {
        return progress;
    }

    public BoardManager board() {
        return board;
    }

    public TrackerBar trackerBar() {
        return trackerBar;
    }

    /**
     * 주기 작업: 액션바 묶음(20틱), 추적 보스바(20틱), 위치 판정(10틱), 추적 방향(balance.yml, 기본 5틱), 게시판 엔티티(600틱), 이벤트 기간
     * 종료(1200틱), 클라이맥스.
     */
    public void start(java.util.function.Consumer<Player> openBoard) {
        board.setOpener(openBoard);
        board.cleanup();
        var _ = engine.tasks.timer(20, 20, tracker::flushBars);
        var _ = engine.tasks.timer(20, 20, trackerBar::refresh);
        var _ = engine.tasks.timer(10, 10, tracker::pollPositions);
        long waypointTicks =
                Math.max(1, engine.balance().integer("quest.tracker.waypoint_refresh_ticks", 5));
        var _ = engine.tasks.timer(40, waypointTicks, tracker::showWaypoints);
        var _ = engine.tasks.timer(40, 600, board::ensureEntity);
        var _ = engine.tasks.timer(1200, 1200, engine::failExpiredEvents);
        progress.start();
    }

    /** 이벤트 구독·NPC 연동·선택 의존 연결·시즌 훅. */
    public void registerHooks(Plugin plugin) {
        new OptionalBridges(engine).register(plugin);
        wiki.creeper.harbor.season.api.SeasonServices.hooks()
                .register(plugin, new QuestSeasonHook());
    }

    public void stop() {
        board.cleanup();
        trackerBar.clear();
    }

    public HandoffBlocker.Verdict handoff(Player player, String destination) {
        return payer.handoff(player);
    }

    public List<Map.Entry<QuestDef, QuestEntry>> open(HarborPlayer hp) {
        return engine.open(hp);
    }

    /** NPC 대화 엔진 (수락 제안·퀘스트 목록을 NPC 대화 방식으로 보여줄 때). */
    public wiki.creeper.harbor.npc.api.DialogService npcDialogs() {
        return engine.deps.dialogs();
    }

    public wiki.creeper.harbor.npc.api.NpcService npcs() {
        return engine.deps.npcs();
    }

    /** 콘텐츠 월드 ID (balance.yml world_alias). */
    public String worldId(org.bukkit.World world) {
        return tracker.worldId(world);
    }

    public Component npcName(Player viewer, NpcId npc) {
        return engine.deps.npcs().displayName(viewer, npc);
    }

    public int remainingPicks(HarborPlayer hp, QuestType kind) {
        return board.remainingPicks(hp, kind);
    }

    public CompletableFuture<BoardState> ensureBoard(Player player, QuestType kind) {
        return board.ensure(player, kind, false);
    }

    /** 보상 미리보기 (수락 전, 지금 지역 기준). */
    public Progress.Snapshot preview(HarborPlayer hp, QuestDef def) {
        return payer.snapshot(hp, def);
    }

    /** 관리 명령: 강제 완료. */
    public CompletableFuture<Boolean> forceComplete(Player player, QuestDef def, boolean reward) {
        return payer.complete(player, def, reward);
    }

    void onJobAdvanced(Player player, int tier) {
        payer.onJobAdvanced(player, tier);
    }

    void recover(Player player) {
        payer.recover(player);
    }

    void forget(java.util.UUID uuid) {
        engine.forget(uuid);
        tracker.forget(uuid);
        trackerBar.forget(uuid);
    }

    void expireBoards(Player player, java.util.Set<QuestType> kinds) {
        board.expire(player, kinds);
    }
}
