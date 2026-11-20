package wiki.creeper.harbor.npc.dialog.present;

import java.util.List;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/** DIALOG: Core Dialog 래퍼의 선택 화면 (기존 동작 그대로, 한 줄에 버튼 하나). */
final class DialogScreenPresenter implements DialogPresenter {

    private final HarborApi api;

    DialogScreenPresenter(HarborApi api) {
        this.api = api;
    }

    @Override
    public void show(Player player, Frame frame) {
        var text = frame.text();
        api.dialogs()
                .choose(
                        player,
                        frame.speaker(),
                        text == null ? List.of() : List.of(text),
                        frame.choices(),
                        1,
                        index -> frame.onClick().accept(index));
    }

    // clear는 하지 않는다: 화면 닫기는 DialogManager가 기존 흐름대로 정하고, Handoff면 다른 모듈 화면이 떠 있다
}
