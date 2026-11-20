package wiki.creeper.harbor.npc.dialog.present;

import java.util.List;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Dialogs;

/**
 * 대화 한 화면 (한 쪽 + 버튼). 버튼을 누르면 {@code onClick}(순서 번호)을 부른다. 오래된 화면·위조 클릭은 받는 쪽(DialogManager)이
 * nonce로 거른다.
 *
 * @param text 대사 (없으면 버튼만)
 * @param nextOnly 버튼이 "다음" 하나뿐인지 (DIALOG 밖의 방식은 NPC 재클릭으로도 넘김)
 * @param staySeconds 화면이 떠 있을 시간 힌트 (타이틀 유지 시간, 0이면 방식 기본값)
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
