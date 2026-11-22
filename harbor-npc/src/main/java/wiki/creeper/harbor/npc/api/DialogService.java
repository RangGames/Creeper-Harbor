package wiki.creeper.harbor.npc.api;

import java.util.Optional;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Dialog engine (docs 20 5.4). Dialog trees are content/dialogs/*.yml; screens are drawn with the
 * Core Dialog wrapper.
 *
 * <p>Condition and action keys are registered as {@code module:name} (e.g. quest:accept, shop:open,
 * job:change_line). Register them in the module's enable (before content loads) so the content
 * check validates arguments too. If a key is unregistered because an optional dependency is
 * missing, that choice is hidden and only a warning is logged.
 *
 * <pre>{@code
 * DialogService dialogs = Harbor.api().service(DialogService.class).orElseThrow();
 * dialogs.registerAction(this, "quest:accept", (ctx, args) -> {
 *     QuestId quest = QuestId.parse(args.value().orElseThrow());
 *     return accept(ctx.player(), quest) ? ActionResult.goTo("accepted") : ActionResult.fail("not_ready");
 * });
 * dialogs.registerCondition(this, "quest_state", (ctx, args) -> ...);
 * }</pre>
 */
public interface DialogService {

    /**
     * Opens an NPC dialog. Evaluates entries top to bottom and shows the first matching node. False
     * if there is no dialog or no matching node.
     */
    boolean open(Player player, NpcId npc);

    /** Opens a specific node directly (follow-up dialogs from other modules, line review). */
    boolean open(Player player, NpcId npc, String node);

    /** Ends the session and closes the screen. */
    void close(Player player);

    Optional<DialogSession> session(Player player);

    /** Whether any player has an open dialog session with this NPC (delays wanderer moves). */
    boolean hasSessionWith(NpcId npc);

    /**
     * IllegalStateException if the same key already exists. Removed automatically when owner is
     * disabled.
     */
    void registerAction(Plugin owner, String key, DialogAction action);

    void registerCondition(Plugin owner, String key, DialogCondition condition);

    boolean hasAction(String key);

    boolean hasCondition(String key);

    // ------------------------------------------------------------------ presentation style
    // (proposed)

    /** NPC default style: npcs.yml dialog_style → balance.yml npc.dialog.default_style → DIALOG. */
    DialogStyle styleOf(NpcId npc);

    /**
     * Shows lines and choices from the NPC's mouth without a dialog tree (quest offers etc.).
     * Session, nonce, time limit and distance checks are the same as for dialog trees. Receives the
     * chosen index (0-based) on the main thread, or -1 on close, expiry, moving away or replacement
     * by another dialog. The result arrives exactly once.
     */
    void present(Player player, NpcId npc, Speech speech, IntConsumer onChoice);

    /**
     * A session-less one-liner (a few seconds as speech bubble, title, action bar or chat). Used
     * for things like a quest turn-in greeting.
     *
     * @param style null for the NPC default style. DIALOG shows a notice screen
     */
    void say(Player player, NpcId npc, Component line, @Nullable DialogStyle style);
}
