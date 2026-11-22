package wiki.creeper.harbor.npc.api;

import net.kyori.adventure.text.Component;

/** One {@link Speech} choice. An empty tooltip means no description. */
public record SpeechChoice(Component label, Component tooltip) {

    public static SpeechChoice of(Component label) {
        return new SpeechChoice(label, Component.empty());
    }
}
