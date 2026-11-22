package wiki.creeper.harbor.npc.dialog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.Speech;
import wiki.creeper.harbor.npc.content.DialogDef;

/**
 * An open dialog (not saved). Either a dialog tree (def) session or a speech session opened by
 * another module. Main thread only.
 */
final class ActiveSession {

    /** Dialog ID that speech sessions use for DialogSession and events. */
    static final HarborId SPEECH_ID = HarborId.parse("dialog/speech");

    /** What one button does. */
    sealed interface Button permits Next, Choose, CloseButton {}

    record Next() implements Button {}

    record Choose(int option) implements Button {}

    record CloseButton() implements Button {}

    final UUID player;
    final NpcId npc;

    /**
     * Dialog definition snapshot taken at start (this session keeps the same definition even after
     * a reload). Null for speech sessions.
     */
    final @Nullable DialogDef def;

    /** Lines opened by another module (null for dialog tree sessions). */
    final @Nullable Speech speech;

    /** Speech result (cleared to null once called). */
    @Nullable IntConsumer onSpeech;

    final Instant openedAt;
    final boolean preview;
    String node;
    int page;

    /** Style and buttons of the last drawn page (for NPC re-clicks in non-DIALOG styles). */
    DialogStyle style = DialogStyle.DIALOG;

    List<Button> buttons = List.of();

    /** When the screen was last shown. Clicks are not accepted after the time limit. */
    Instant shownAt = Instant.now();

    long nonce;
    boolean busy;

    /** Scheduled auto-advance (cancelled when the page changes). */
    @Nullable BukkitTask auto;

    ActiveSession(UUID player, NpcId npc, DialogDef def, String node, boolean preview) {
        this.player = player;
        this.npc = npc;
        this.def = def;
        this.speech = null;
        this.node = node;
        this.preview = preview;
        this.openedAt = Instant.now();
    }

    ActiveSession(UUID player, NpcId npc, Speech speech, IntConsumer onSpeech) {
        this.player = player;
        this.npc = npc;
        this.def = null;
        this.speech = speech;
        this.onSpeech = onSpeech;
        this.node = "";
        this.preview = false;
        this.openedAt = Instant.now();
    }

    HarborId dialogId() {
        DialogDef d = def;
        return d == null ? SPEECH_ID : d.id();
    }

    void cancelAuto() {
        BukkitTask t = auto;
        if (t != null) {
            t.cancel();
            auto = null;
        }
    }

    /** Delivers the speech result only once. */
    void resolve(int choice) {
        IntConsumer callback = takeCallback();
        if (callback != null) {
            callback.accept(choice);
        }
    }

    /** Takes out the speech result callback (later resolve calls do nothing). */
    @Nullable IntConsumer takeCallback() {
        IntConsumer callback = onSpeech;
        onSpeech = null;
        return callback;
    }
}
