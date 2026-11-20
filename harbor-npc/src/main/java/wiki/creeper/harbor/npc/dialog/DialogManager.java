package wiki.creeper.harbor.npc.dialog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogCondition;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogEndReason;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.DialogSession;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.Speech;
import wiki.creeper.harbor.npc.api.SpeechChoice;
import wiki.creeper.harbor.npc.api.event.DialogEndEvent;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.dialog.ActiveSession.Button;
import wiki.creeper.harbor.npc.dialog.ActiveSession.Choose;
import wiki.creeper.harbor.npc.dialog.ActiveSession.CloseButton;
import wiki.creeper.harbor.npc.dialog.ActiveSession.Next;
import wiki.creeper.harbor.npc.dialog.present.Frame;
import wiki.creeper.harbor.npc.dialog.present.Presenters;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.DialogEval;
import wiki.creeper.harbor.npc.logic.SpeechText;

/**
 * 대화 엔진 (docs 20 5.4 상호작용·대화 흐름 4~6단계). 쪽·버튼을 정하고 그리기는 방식별 {@link Presenters}에 맡긴다 (DIALOG = Core
 * Dialog 래퍼). 버튼마다 세션 nonce를 실어 오래된 화면·위조 클릭을 무시한다. 클릭 때 세션 시간·NPC 거리를 확인하고 선택지 조건을 다시 평가한 뒤 액션을
 * 순서대로 실행한다 ({@link ActionRunner}). 메인 스레드 전용.
 */
public final class DialogManager implements DialogService, Listener {

    private final HarborApi api;
    private final Tasks tasks;
    private final NpcManager npcs;
    private final DialogRegistry registry;
    private final Presenters presenters;
    private final ActionRunner actions;
    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private long nonceSeq = System.nanoTime();

    public DialogManager(
            HarborApi api,
            Tasks tasks,
            NpcManager npcs,
            DialogRegistry registry,
            Presenters presenters,
            Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.npcs = npcs;
        this.registry = registry;
        this.presenters = presenters;
        this.actions = new ActionRunner(api, tasks, registry, log, this);
    }

    public void start() {
        // 만료된 세션 정리 (ESC로 닫은 화면은 서버가 모르므로 시간으로 끝낸다)
        var _ = tasks.timer(600, 600, this::expireSessions);
        // DIALOG 밖의 방식: 멀어지면 끝내고, 말풍선·액션바를 유지한다
        int watch = balance().watchTicks();
        var _ = tasks.timer(watch, watch, this::watch);
    }

    /** 플러그인 종료: 말풍선을 모두 지운다. */
    public void shutdown() {
        for (ActiveSession s : List.copyOf(sessions.values())) {
            end(s, DialogEndReason.CLOSED);
        }
        presenters.shutdown();
    }

    NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    Map<UUID, ActiveSession> sessions() {
        return sessions;
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
        begin(player, new ActiveSession(player.getUniqueId(), npc, def.get(), node.get(), false));
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
        begin(player, new ActiveSession(player.getUniqueId(), npc, def.get(), node, preview));
        return true;
    }

    private Optional<DialogDef> dialogOf(NpcId npc) {
        return npcs.def(npc)
                .map(NpcDef::dialog)
                .flatMap(id -> api.content().snapshot().get(DialogDef.TYPE, id));
    }

    private void begin(Player player, ActiveSession s) {
        ActiveSession previous = sessions.get(player.getUniqueId());
        if (previous != null) {
            end(previous, DialogEndReason.REPLACED);
            ActiveSession reopened = sessions.get(player.getUniqueId());
            if (reopened != null) {
                end(reopened, DialogEndReason.REPLACED); // 이전 결과 처리에서 또 연 세션
            }
        }
        sessions.put(player.getUniqueId(), s);
        render(player, s);
    }

    /**
     * NPC 재클릭: DIALOG 밖의 방식으로 이 NPC와 대화 중이면 다음 쪽으로 넘기거나 선택지를 다시 보여주고 true. 아니면 false (새 대화를 연다).
     */
    public boolean advance(Player player, NpcId npc) {
        ActiveSession s = sessions.get(player.getUniqueId());
        if (s == null || !s.npc.equals(npc) || s.style.modal()) {
            return false;
        }
        if (s.busy) {
            return true; // 액션 처리 중: 새로 열지 않고 기다린다
        }
        if (s.buttons.size() == 1 && s.buttons.getFirst() instanceof Next) {
            onClick(player, s.nonce, s.buttons.getFirst());
        } else {
            render(player, s);
        }
        return true;
    }

    @Override
    public void close(Player player) {
        ActiveSession s = sessions.get(player.getUniqueId());
        if (s != null) {
            end(s, DialogEndReason.CLOSED);
        }
        player.closeDialog();
    }

    @Override
    public Optional<DialogSession> session(Player player) {
        ActiveSession s = sessions.get(player.getUniqueId());
        return s == null
                ? Optional.empty()
                : Optional.of(new DialogSession(s.npc, s.dialogId(), s.node, s.page, s.openedAt));
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

    @Override
    public DialogStyle styleOf(NpcId npc) {
        Optional<NpcDef> def = npcs.def(npc);
        if (def.isPresent()) {
            DialogStyle style = def.get().dialogStyle();
            if (style != null) {
                return style;
            }
        }
        return balance().defaultStyle();
    }

    @Override
    public void present(Player player, NpcId npc, Speech speech, IntConsumer onChoice) {
        begin(player, new ActiveSession(player.getUniqueId(), npc, speech, onChoice));
    }

    @Override
    public void say(Player player, NpcId npc, Component line, @Nullable DialogStyle style) {
        presenters.say(
                player,
                npc,
                npcs.displayName(player, npc),
                line,
                style == null ? styleOf(npc) : style);
    }

    // ------------------------------------------------------------------ 평가

    /** 조건 하나. 등록되지 않은 키는 거짓 (선택지를 숨김). */
    boolean test(DialogContext ctx, DialogRef ref) {
        return actions.test(ctx, ref);
    }

    // ------------------------------------------------------------------ 화면

    void render(Player player, ActiveSession s) {
        DialogDef def = s.def;
        if (def == null) {
            renderSpeech(player, s);
            return;
        }
        DialogDef.Node node = def.nodes().get(s.node);
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
        Component body =
                node.pages().isEmpty()
                        ? null
                        : api.messages()
                                .get(
                                        node.pages().get(s.page),
                                        Placeholder.unparsed("player", player.getName()));
        List<Dialogs.Choice> choices = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (!DialogEval.optionsOnPage(node, s.page)) {
            choices.add(choice("npc.dialog.next", null));
            buttons.add(new Next());
        } else {
            for (int i = 0; i < node.options().size(); i++) {
                DialogDef.Option option = node.options().get(i);
                if (actions.available(ctx, option)) {
                    choices.add(choice(option.label(), option.tooltip()));
                    buttons.add(new Choose(i));
                }
            }
            if (buttons.isEmpty()) {
                choices.add(choice("npc.dialog.close", null));
                buttons.add(new CloseButton());
            }
        }
        DialogStyle style = node.style() != null ? node.style() : styleOf(s.npc);
        show(player, s, style, title, body, choices, buttons, node.autoSeconds());
    }

    private void renderSpeech(Player player, ActiveSession s) {
        Speech speech = s.speech;
        if (speech == null) {
            end(s, DialogEndReason.CLOSED);
            return;
        }
        int pages = speech.pages().size();
        s.page = DialogEval.clampPage(pages, s.page);
        Component speaker = speech.speaker();
        Component title = speaker != null ? speaker : npcs.displayName(player, s.npc);
        Component body = pages == 0 ? null : speech.pages().get(s.page);
        List<Dialogs.Choice> choices = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (!DialogEval.optionsOnPage(pages, s.page)) {
            choices.add(choice("npc.dialog.next", null));
            buttons.add(new Next());
        } else if (speech.choices().isEmpty()) {
            choices.add(choice("npc.dialog.close", null));
            buttons.add(new CloseButton());
        } else {
            for (int i = 0; i < speech.choices().size(); i++) {
                SpeechChoice c = speech.choices().get(i);
                choices.add(new Dialogs.Choice(c.label(), c.tooltip()));
                buttons.add(new Choose(i));
            }
        }
        DialogStyle style = speech.style() != null ? speech.style() : styleOf(s.npc);
        show(player, s, style, title, body, choices, buttons, 0);
    }

    private void show(
            Player player,
            ActiveSession s,
            DialogStyle style,
            Component title,
            @Nullable Component body,
            List<Dialogs.Choice> choices,
            List<Button> buttons,
            double autoSeconds) {
        if (style != s.style) {
            presenters.clear(player, s.style); // 노드마다 방식이 바뀔 수 있다 (말풍선 → Dialog 화면)
        }
        s.cancelAuto();
        long nonce = ++nonceSeq;
        s.nonce = nonce;
        s.shownAt = Instant.now();
        s.style = style;
        s.buttons = List.copyOf(buttons);
        boolean nextOnly = buttons.size() == 1 && buttons.getFirst() instanceof Next;
        presenters
                .of(style)
                .show(
                        player,
                        new Frame(
                                s.npc,
                                title,
                                body,
                                choices,
                                nextOnly,
                                autoSeconds,
                                index -> {
                                    if (index >= 0 && index < buttons.size()) {
                                        onClick(player, nonce, buttons.get(index));
                                    }
                                }));
        long autoTicks = SpeechText.autoTicks(autoSeconds);
        if (!style.modal() && nextOnly && autoTicks > 0) {
            s.auto = tasks.later(autoTicks, () -> onClick(player, nonce, new Next()));
        }
    }

    private Dialogs.Choice choice(String label, @Nullable String tooltip) {
        return new Dialogs.Choice(
                api.messages().get(label),
                tooltip == null ? Component.empty() : api.messages().get(tooltip));
    }

    DialogContext context(Player player, ActiveSession s) {
        return new DialogContext(player, s.npc, s.dialogId(), s.node);
    }

    // ------------------------------------------------------------------ 클릭

    private void onClick(Player player, long nonce, Button button) {
        ActiveSession s = sessions.get(player.getUniqueId());
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
            case CloseButton() -> {
                end(s, DialogEndReason.CLOSED);
                if (s.def == null && s.style.modal()) {
                    player.closeDialog();
                }
            }
            case Choose(int index) -> {
                if (s.def == null) {
                    // 먼저 세션을 닫고 결과를 넘긴다 (결과 처리에서 새 화면을 열어도 닫히지 않게)
                    IntConsumer callback = s.takeCallback();
                    end(s, DialogEndReason.CLOSED);
                    if (s.style.modal()) {
                        player.closeDialog();
                    }
                    if (callback != null) {
                        callback.accept(index);
                    }
                } else {
                    actions.choose(player, s, index);
                }
            }
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

    void goTo(Player player, ActiveSession s, String node, Logger log) {
        DialogDef def = s.def;
        if (def == null || !def.nodes().containsKey(node)) {
            log.warn("대화 {}에 노드 '{}'가 없어 닫습니다", s.dialogId(), node);
            end(s, DialogEndReason.CLOSED);
            player.closeDialog();
            return;
        }
        s.node = node;
        s.page = 0;
        render(player, s);
    }

    void end(ActiveSession s, DialogEndReason reason) {
        if (sessions.get(s.player) != s) {
            return;
        }
        sessions.remove(s.player);
        s.nonce = -1;
        s.cancelAuto();
        Player player = Bukkit.getPlayer(s.player);
        if (player == null) {
            presenters.forget(s.player);
        } else {
            presenters.clear(player, s.style);
            DialogDef def = s.def;
            if (def != null) {
                Bukkit.getPluginManager()
                        .callEvent(new DialogEndEvent(player, s.npc, def.id(), s.node, reason));
            }
        }
        // 마지막에 넘긴다: 결과 처리에서 새 대화를 열어도 위 정리가 그 화면을 지우지 않게
        s.resolve(-1);
    }

    private void expireSessions() {
        Instant limit = Instant.now().minusSeconds(balance().dialogTimeoutSeconds());
        for (ActiveSession s : List.copyOf(sessions.values())) {
            if (s.shownAt.isBefore(limit) && !s.busy) {
                end(s, DialogEndReason.EXPIRED);
            }
        }
    }

    /** DIALOG 밖의 방식: 멀어지면 끝내고(말풍선 정리), 표시를 유지한다. */
    private void watch() {
        double distance = balance().optionDistance();
        for (ActiveSession s : List.copyOf(sessions.values())) {
            if (s.style.modal() || s.preview || s.busy) {
                continue;
            }
            Player player = Bukkit.getPlayer(s.player);
            if (player != null && !nearNpc(player, s.npc, distance)) {
                api.messages().send(player, "npc.dialog.too_far");
                end(s, DialogEndReason.EXPIRED);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveSession s = sessions.get(player.getUniqueId());
            if ((s != null && !s.style.modal()) || presenters.needsRefresh(player.getUniqueId())) {
                presenters.refresh(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ActiveSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            end(s, DialogEndReason.QUIT);
        }
        presenters.forget(event.getPlayer().getUniqueId());
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
