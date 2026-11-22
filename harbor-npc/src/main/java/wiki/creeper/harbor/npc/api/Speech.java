package wiki.creeper.harbor.npc.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Lines another module shows from an NPC's mouth without a dialog tree (quest offers etc.). Open
 * with {@link DialogService#present}.
 *
 * @param style presentation style (null for the NPC default {@link DialogService#styleOf})
 * @param speaker speaker (null for the NPC display name)
 * @param pages pages of lines. Choices are shown on the last page
 * @param choices choices (empty for a single close button)
 */
public record Speech(
        @Nullable DialogStyle style,
        @Nullable Component speaker,
        List<Component> pages,
        List<SpeechChoice> choices) {

    public Speech {
        pages = List.copyOf(pages);
        choices = List.copyOf(choices);
    }
}
