package wiki.creeper.harbor.npc.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * 다른 모듈이 대화 트리 없이 NPC 입으로 보여주는 대사 (퀘스트 수락 제안 등). {@link DialogService#present}로 연다.
 *
 * @param style 보여주는 방식 (null이면 NPC 기본 {@link DialogService#styleOf})
 * @param speaker 화자 (null이면 NPC 표시 이름)
 * @param pages 대사 쪽. 마지막 쪽에서 선택지를 보여준다
 * @param choices 선택지 (비우면 닫기 버튼 하나)
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
