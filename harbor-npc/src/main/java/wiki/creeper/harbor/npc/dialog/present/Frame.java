package wiki.creeper.harbor.npc.dialog.present;

import java.util.List;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Dialogs;

/**
 * One dialog screen (one page + buttons). Pressing a button calls {@code onClick} (its index).
 * Stale screens and forged clicks are filtered by the receiver (DialogManager) via the nonce.
 *
 * @param text line (buttons only if absent)
 * @param nextOnly whether the only button is "next" (non-DIALOG styles also advance on NPC
 *     re-click)
 * @param staySeconds hint for how long the screen stays up (title stay time, 0 for the style
 *     default)
 */
public record Frame(
        NpcId npc,
        Component speaker,
        @Nullable Component text,
        List<Dialogs.Choice> choices,
        boolean nextOnly,
        double staySeconds,
        IntConsumer onClick) {

    public Frame {
        choices = List.copyOf(choices);
    }
}
