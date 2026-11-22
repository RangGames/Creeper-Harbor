package wiki.creeper.harbor.npc.dialog.present;

import java.util.List;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * DIALOG: choice screen of the Core Dialog wrapper (existing behavior unchanged, one button per
 * row).
 */
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

    // No clear here: DialogManager decides screen closing via the existing flow, and on Handoff
    // another module's screen is open
}
