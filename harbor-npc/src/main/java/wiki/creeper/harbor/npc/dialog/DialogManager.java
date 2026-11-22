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
 * Dialog engine (docs 20 5.4 interaction and dialog flow steps 4-6). Decides pages and buttons and
 * delegates drawing to the per-style {@link Presenters} (DIALOG = Core Dialog wrapper). Every
 * button carries the session nonce so stale screens and forged clicks are ignored. On click, checks
 * session time and NPC distance, re-evaluates choice conditions, then runs actions in order ({@link
 * ActionRunner}). Main thread only.
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
        // Clean up expired sessions (the server does not know about screens closed with ESC, so end
        // them by time)
        var _ = tasks.timer(600, 600, this::expireSessions);
        // Non-DIALOG styles: end when the player moves away, and keep the speech bubble and action
        // bar up
        int watch = balance().watchTicks();
        var _ = tasks.timer(watch, watch, this::watch);
    }

    /** Plugin shutdown: removes all speech bubbles. */
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

    /** /harbor npc dialog: opens a node without the distance check (line review). */
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
                end(
                        reopened,
                        DialogEndReason
                                .REPLACED); // A session opened again while handling the previous
                // result
            }
        }
        sessions.put(player.getUniqueId(), s);
        render(player, s);
    }

    /**
     * NPC re-click: if talking with this NPC in a non-DIALOG style, advance to the next page or
     * show the choices again and return true. Otherwise false (open a new dialog).
     */
    public boolean advance(Player player, NpcId npc) {
        ActiveSession s = sessions.get(player.getUniqueId());
        if (s == null || !s.npc.equals(npc) || s.style.modal()) {
            return false;
        }
        if (s.busy) {
            return true; // Actions in progress: wait instead of opening a new one
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

    // ------------------------------------------------------------------ evaluation

    /** A single condition. Unregistered keys are false (hide the choice). */
    boolean test(DialogContext ctx, DialogRef ref) {
        return actions.test(ctx, ref);
    }

    // ------------------------------------------------------------------ screens

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
            presenters.clear(
                    player,
                    s.style); // The style can change per node (speech bubble → Dialog screen)
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

    // ------------------------------------------------------------------ clicks

    private void onClick(Player player, long nonce, Button button) {
        ActiveSession s = sessions.get(player.getUniqueId());
        if (s == null || s.nonce != nonce || s.busy || !player.isOnline()) {
            return; // Stale screen, duplicate click, or in progress
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
                    // Close the session first, then deliver the result (so a new screen opened
                    // while handling the result is not closed)
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
        // Deliver last: so the cleanup above does not remove a new dialog opened while handling the
        // result
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

    /**
     * Non-DIALOG styles: end when the player moves away (clean up the bubble), otherwise keep the
     * display up.
     */
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

    /** Looks up a definition by dialog ID (command tab completion etc.). */
    public Optional<DialogDef> dialog(HarborId id) {
        return api.content().snapshot().get(DialogDef.TYPE, id);
    }
}
