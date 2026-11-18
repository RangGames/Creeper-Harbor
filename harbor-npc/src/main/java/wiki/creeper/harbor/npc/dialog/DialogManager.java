package wiki.creeper.harbor.npc.dialog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogCondition;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogEndReason;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.DialogSession;
import wiki.creeper.harbor.npc.api.event.DialogEndEvent;
import wiki.creeper.harbor.npc.api.event.DialogOptionSelectedEvent;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.DialogEval;

/**
 * 대화 엔진 (docs 20 5.4 상호작용·대화 흐름 4~6단계). 화면은 Core Dialog 래퍼(choose)로 그리고, 버튼마다 세션 nonce를 실어 오래된
 * 화면·위조 클릭을 무시한다. 클릭 때 세션 5분·NPC 6블록 이내를 확인하고 선택지 조건을 다시 평가한 뒤 액션을 순서대로 실행한다. 메인 스레드 전용.
 */
public final class DialogManager implements DialogService, Listener {

    /** 열려 있는 대화 (저장하지 않음). */
    static final class Session {
        final UUID player;
        final NpcId npc;

        /** 시작할 때 잡은 대화 정의 스냅샷 (리로드돼도 이 세션은 끝까지 같은 정의). */
        final DialogDef def;

        final Instant openedAt;
        final boolean preview;
        String node;
        int page;

        /** 마지막으로 화면을 띄운 시각. 5분이 지나면 클릭을 받지 않는다. */
        Instant shownAt = Instant.now();

        long nonce;
        boolean busy;

        Session(UUID player, NpcId npc, DialogDef def, String node, boolean preview) {
            this.player = player;
            this.npc = npc;
            this.def = def;
            this.node = node;
            this.preview = preview;
            this.openedAt = Instant.now();
        }
    }

    /** 버튼 하나가 하는 일. */
    private sealed interface Button permits Next, Choose, CloseButton {}

    private record Next() implements Button {}

    private record Choose(int option) implements Button {}

    private record CloseButton() implements Button {}

    private final HarborApi api;
    private final Tasks tasks;
    private final NpcManager npcs;
    private final DialogRegistry registry;
    private final Logger log;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long nonceSeq = System.nanoTime();

    public DialogManager(
            HarborApi api, Tasks tasks, NpcManager npcs, DialogRegistry registry, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.npcs = npcs;
        this.registry = registry;
        this.log = log;
    }

    public void start() {
        // 만료된 세션 정리 (ESC로 닫은 화면은 서버가 모르므로 시간으로 끝낸다)
        var _ = tasks.timer(600, 600, this::expireSessions);
    }

    private NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    // ------------------------------------------------------------------ DialogService

    @Override
    public boolean open(Player player, NpcId npc) {
        Optional<DialogDef> def = dialogOf(npc);
        if (def.isEmpty()) {
            return false;
        }
        DialogContext entryCtx = new DialogContext(player, npc, def.get().id(), "");
        Optional<String> node = DialogEval.entry(def.get(), ref -> test(entryCtx, ref));
        if (node.isEmpty()) {
            return false;
        }
        begin(player, npc, def.get(), node.get(), false);
        return true;
    }

    @Override
    public boolean open(Player player, NpcId npc, String node) {
        return openNode(player, npc, node, false);
    }

    /** /harbor npc dialog: 거리 검사 없이 노드를 연다 (대사 검수). */
    public boolean preview(Player player, NpcId npc, String node) {
        return openNode(player, npc, node, true);
    }

    private boolean openNode(Player player, NpcId npc, String node, boolean preview) {
        Optional<DialogDef> def = dialogOf(npc);
        if (def.isEmpty() || !def.get().nodes().containsKey(node)) {
            return false;
        }
        begin(player, npc, def.get(), node, preview);
        return true;
    }

    private Optional<DialogDef> dialogOf(NpcId npc) {
        return npcs.def(npc)
                .map(NpcDef::dialog)
                .flatMap(id -> api.content().snapshot().get(DialogDef.TYPE, id));
    }

    private void begin(Player player, NpcId npc, DialogDef def, String node, boolean preview) {
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null) {
            end(previous, DialogEndReason.REPLACED);
        }
        Session s = new Session(player.getUniqueId(), npc, def, node, preview);
        sessions.put(player.getUniqueId(), s);
        render(player, s);
    }

    @Override
    public void close(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            end(s, DialogEndReason.CLOSED);
        }
        player.closeDialog();
    }

    @Override
    public Optional<DialogSession> session(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s == null
                ? Optional.empty()
                : Optional.of(new DialogSession(s.npc, s.def.id(), s.node, s.page, s.openedAt));
    }

    @Override
    public boolean hasSessionWith(NpcId npc) {
        Duration timeout = Duration.ofSeconds(balance().dialogTimeoutSeconds());
        Instant now = Instant.now();
        return sessions.values().stream()
                .anyMatch(s -> s.npc.equals(npc) && s.shownAt.plus(timeout).isAfter(now));
    }

    @Override
    public void registerAction(Plugin owner, String key, DialogAction action) {
        registry.action(owner, key, action);
    }

    @Override
    public void registerCondition(Plugin owner, String key, DialogCondition condition) {
        registry.condition(owner, key, condition);
    }

    @Override
    public boolean hasAction(String key) {
        return registry.hasAction(key);
    }

    @Override
    public boolean hasCondition(String key) {
        return registry.hasCondition(key);
    }

    // ------------------------------------------------------------------ 평가

    /** 조건 하나. 등록되지 않은 키는 거짓 (선택지를 숨김). */
    boolean test(DialogContext ctx, DialogRef ref) {
        Optional<DialogCondition> condition = registry.condition(ref.key());
        if (condition.isEmpty()) {
            registry.warnOnce(ref, "조건");
            return false;
        }
        try {
            return condition.get().test(ctx, ref.args());
        } catch (RuntimeException e) {
            log.warn("대화 조건 {} 평가 오류 ({})", ref.key(), ref.pos(), e);
            return false;
        }
    }

    /** 선택지를 보여줄지: 조건이 맞고, 액션 키가 모두 등록되어 있어야 한다. */
    private boolean available(DialogContext ctx, DialogDef.Option option) {
        for (DialogRef action : option.actions()) {
            if (!registry.hasAction(action.key())) {
                registry.warnOnce(action, "액션");
                return false;
            }
        }
        return DialogEval.all(option.conditions(), ref -> test(ctx, ref));
    }

    // ------------------------------------------------------------------ 화면

    private void render(Player player, Session s) {
        DialogDef.Node node = s.def.nodes().get(s.node);
        if (node == null) {
            end(s, DialogEndReason.CLOSED);
            return;
        }
        s.page = DialogEval.clampPage(node, s.page);
        DialogContext ctx = context(player, s);
        Component title =
                node.speaker() != null
                        ? MiniMessage.miniMessage().deserialize(node.speaker())
                        : npcs.displayName(player, s.npc);
        List<Component> body = new ArrayList<>();
        if (!node.pages().isEmpty()) {
            body.add(
                    api.messages()
                            .get(
                                    node.pages().get(s.page),
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                            .unparsed("player", player.getName())));
        }
        List<Dialogs.Choice> choices = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (!DialogEval.optionsOnPage(node, s.page)) {
            choices.add(choice("npc.dialog.next", null));
            buttons.add(new Next());
        } else {
            for (int i = 0; i < node.options().size(); i++) {
                DialogDef.Option option = node.options().get(i);
                if (available(ctx, option)) {
                    choices.add(choice(option.label(), option.tooltip()));
                    buttons.add(new Choose(i));
                }
            }
            if (buttons.isEmpty()) {
                choices.add(choice("npc.dialog.close", null));
                buttons.add(new CloseButton());
            }
        }
        long nonce = ++nonceSeq;
        s.nonce = nonce;
        s.shownAt = Instant.now();
        api.dialogs()
                .choose(
                        player,
                        title,
                        body,
                        choices,
                        1,
                        index -> onClick(player, nonce, buttons.get(index)));
    }

    private Dialogs.Choice choice(String label, @Nullable String tooltip) {
        return new Dialogs.Choice(
                api.messages().get(label),
                tooltip == null ? Component.empty() : api.messages().get(tooltip));
    }

    private DialogContext context(Player player, Session s) {
        return new DialogContext(player, s.npc, s.def.id(), s.node);
    }

    // ------------------------------------------------------------------ 클릭

    private void onClick(Player player, long nonce, Button button) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.nonce != nonce || s.busy || !player.isOnline()) {
            return; // 오래된 화면·중복 클릭·처리 중
        }
        NpcBalance balance = balance();
        if (s.shownAt.plusSeconds(balance.dialogTimeoutSeconds()).isBefore(Instant.now())) {
            api.messages().send(player, "npc.dialog.expired");
            end(s, DialogEndReason.EXPIRED);
            return;
        }
        if (!s.preview && !nearNpc(player, s.npc, balance.optionDistance())) {
            api.messages().send(player, "npc.dialog.too_far");
            end(s, DialogEndReason.EXPIRED);
            return;
        }
        switch (button) {
            case Next() -> {
                s.page++;
                render(player, s);
            }
            case CloseButton() -> end(s, DialogEndReason.CLOSED);
            case Choose(int index) -> choose(player, s, index);
        }
    }

    private boolean nearNpc(Player player, NpcId npc, double distance) {
        Location at = npcs.home(npc);
        if (at == null) {
            return npcs.isNear(player, npc, distance);
        }
        return at.getWorld().equals(player.getWorld())
                && at.distanceSquared(Worlds.loc(player)) <= distance * distance;
    }

    private void choose(Player player, Session s, int index) {
        DialogDef.Node node = s.def.nodes().get(s.node);
        if (node == null || index >= node.options().size()) {
            end(s, DialogEndReason.CLOSED);
            return;
        }
        DialogDef.Option option = node.options().get(index);
        // 화면을 띄운 뒤 상태가 바뀌었을 수 있어 다시 평가한다
        if (!available(context(player, s), option)) {
            api.messages().send(player, "npc.dialog.unavailable");
            render(player, s);
            return;
        }
        Bukkit.getPluginManager()
                .callEvent(new DialogOptionSelectedEvent(player, s.npc, s.def.id(), s.node, index));
        runActions(player, s, option, 0);
    }

    private void runActions(Player player, Session s, DialogDef.Option option, int from) {
        List<DialogRef> actions = option.actions();
        for (int i = from; i < actions.size(); i++) {
            DialogRef ref = actions.get(i);
            Optional<DialogAction> action = registry.action(ref.key());
            if (action.isEmpty()) {
                registry.warnOnce(ref, "액션");
                continue;
            }
            ActionResult result;
            try {
                result = action.get().run(context(player, s), ref.args());
            } catch (RuntimeException e) {
                log.error("대화 액션 {} 실행 오류 ({})", ref.key(), ref.pos(), e);
                result = ActionResult.failMessage("npc.dialog.error");
            }
            if (result instanceof ActionResult.Continue) {
                continue;
            }
            if (result instanceof ActionResult.Async(var future)) {
                int next = i + 1;
                s.busy = true;
                long nonce = s.nonce;
                var _ =
                        future.whenCompleteAsync(
                                (r, ex) -> {
                                    s.busy = false;
                                    if (sessions.get(s.player) != s
                                            || s.nonce != nonce
                                            || !player.isOnline()) {
                                        return; // 그 사이 세션이 끝났거나 바뀜
                                    }
                                    ActionResult done = r;
                                    if (ex != null || done == null) {
                                        log.error("대화 액션 {} 비동기 오류 ({})", ref.key(), ref.pos(), ex);
                                        done = ActionResult.failMessage("npc.dialog.error");
                                    }
                                    if (done instanceof ActionResult.Continue) {
                                        runActions(player, s, option, next);
                                    } else {
                                        apply(player, s, done);
                                    }
                                },
                                tasks.main());
                return;
            }
            apply(player, s, result);
            return;
        }
        String target = option.gotoNode();
        if (target != null) {
            goTo(player, s, target);
        } else {
            end(s, DialogEndReason.CLOSED);
            player.closeDialog();
        }
    }

    /** Continue·Async가 아닌 결과 처리. */
    private void apply(Player player, Session s, ActionResult result) {
        switch (result) {
            case ActionResult.GoTo(String node) -> goTo(player, s, node);
            case ActionResult.Page(int page) -> {
                s.page = page;
                render(player, s);
            }
            case ActionResult.Close() -> {
                end(s, DialogEndReason.CLOSED);
                player.closeDialog();
            }
            case ActionResult.Handoff() -> end(s, DialogEndReason.HANDOFF);
            case ActionResult.Fail(String node, String key) -> {
                if (key != null) {
                    api.messages().send(player, key);
                }
                if (node != null && s.def.nodes().containsKey(node)) {
                    goTo(player, s, node);
                } else {
                    render(player, s);
                }
            }
            case ActionResult.Continue c -> render(player, s);
            case ActionResult.Async a -> render(player, s);
        }
    }

    private void goTo(Player player, Session s, String node) {
        if (!s.def.nodes().containsKey(node)) {
            log.warn("대화 {}에 노드 '{}'가 없어 닫습니다", s.def.id(), node);
            end(s, DialogEndReason.CLOSED);
            player.closeDialog();
            return;
        }
        s.node = node;
        s.page = 0;
        render(player, s);
    }

    private void end(Session s, DialogEndReason reason) {
        if (sessions.get(s.player) != s) {
            return;
        }
        sessions.remove(s.player);
        s.nonce = -1;
        Player player = Bukkit.getPlayer(s.player);
        if (player != null) {
            Bukkit.getPluginManager()
                    .callEvent(new DialogEndEvent(player, s.npc, s.def.id(), s.node, reason));
        }
    }

    private void expireSessions() {
        Instant limit = Instant.now().minusSeconds(balance().dialogTimeoutSeconds());
        for (Session s : List.copyOf(sessions.values())) {
            if (s.shownAt.isBefore(limit) && !s.busy) {
                end(s, DialogEndReason.EXPIRED);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            end(s, DialogEndReason.QUIT);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        registry.unregisterAll(event.getPlugin());
    }

    /** 대화 ID로 정의 조회 (명령 자동 완성 등). */
    public Optional<DialogDef> dialog(HarborId id) {
        return api.content().snapshot().get(DialogDef.TYPE, id);
    }
}
