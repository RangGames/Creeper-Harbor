package wiki.creeper.harbor.npc.api;

import net.kyori.adventure.text.Component;

/** {@link Speech} 선택지 하나. tooltip이 비어 있으면 설명 없음. */
public record SpeechChoice(Component label, Component tooltip) {

    public static SpeechChoice of(Component label) {
        return new SpeechChoice(label, Component.empty());
    }
}
