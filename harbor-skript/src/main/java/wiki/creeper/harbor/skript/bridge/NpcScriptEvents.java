package wiki.creeper.harbor.skript.bridge;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogContext;

/**
 * Bukkit events Harbor-Skript fires itself so scripts can implement NPC dialog actions and
 * conditions ({@code skript:action} · {@code skript:check}) and receive choices of lines shown with
 * {@code harbor npc … present}. Fired on the main thread; scripts set the result inside the handler
 * (before any {@code wait}).
 */
public final class NpcScriptEvents {

    private NpcScriptEvents() {}

    /** Common part: the dialog situation and the script ID ({@code value} argument). */
    public abstract static class DialogScriptEvent extends Event {
        private final DialogContext ctx;
        private final DialogArgs args;

        DialogScriptEvent(DialogContext ctx, DialogArgs args) {
            this.ctx = ctx;
            this.args = args;
        }

        Player player() {
            return ctx.player();
        }

        NpcId npc() {
            return ctx.npc();
        }

        HarborId dialog() {
            return ctx.dialog();
        }

        String node() {
            return ctx.node();
        }

        /** Script ID (the action's {@code value} argument, empty string if missing). */
        String id() {
            return args.value().orElse("");
        }

        DialogArgs args() {
            return args;
        }
    }

    /** Dialog action {@code skript:action}. The result defaults to continue. */
    public static final class DialogActionEvent extends DialogScriptEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private ActionResult result = ActionResult.CONTINUE;

        DialogActionEvent(DialogContext ctx, DialogArgs args) {
            super(ctx, args);
        }

        ActionResult result() {
            return result;
        }

        void result(ActionResult result) {
            this.result = result;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Dialog condition {@code skript:check}. Fails unless a script passes it. */
    public static final class DialogConditionEvent extends DialogScriptEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean pass;

        DialogConditionEvent(DialogContext ctx, DialogArgs args) {
            super(ctx, args);
        }

        boolean pass() {
            return pass;
        }

        void pass(boolean pass) {
            this.pass = pass;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Choice of lines shown with {@code harbor npc … present} (-1 = closed or expired). */
    public static final class SpeechChoiceEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final NpcId npc;
        private final @Nullable String tag;
        private final int choice;

        SpeechChoiceEvent(Player player, NpcId npc, @Nullable String tag, int choice) {
            this.player = player;
            this.npc = npc;
            this.tag = tag;
            this.choice = choice;
        }

        Player player() {
            return player;
        }

        NpcId npc() {
            return npc;
        }

        @Nullable String tag() {
            return tag;
        }

        int choice() {
            return choice;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
