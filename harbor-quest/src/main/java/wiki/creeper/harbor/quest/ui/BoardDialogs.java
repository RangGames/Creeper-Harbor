package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.BoardState;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * Fishermen's guild board screens (daily and weekly request lists) used by {@link QuestDialogs}.
 */
final class BoardDialogs {

    private final HarborApi api;
    private final QuestServices q;
    private final QuestDialogs dialogs;

    BoardDialogs(HarborApi api, QuestServices q, QuestDialogs dialogs) {
        this.api = api;
        this.q = q;
        this.dialogs = dialogs;
    }

    private MessageService msg() {
        return api.messages();
    }

    private QuestEngine engine() {
        return q.engine();
    }

    /** Board home screen: daily and weekly. */
    void boardRoot(Player player) {
        HarborPlayer hp = api.players().get(player);
        List<Dialogs.Choice> choices =
                List.of(
                        new Dialogs.Choice(
                                msg().get(
                                                "quest.board.daily",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, QuestType.DAILY)))),
                                Component.empty()),
                        new Dialogs.Choice(
                                msg().get(
                                                "quest.board.weekly",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, QuestType.WEEKLY)))),
                                Component.empty()));
        api.dialogs()
                .choose(
                        player,
                        msg().get("quest.board.title"),
                        List.of(msg().get("quest.board.body")),
                        choices,
                        2,
                        i -> board(player, i == 0 ? QuestType.DAILY : QuestType.WEEKLY));
    }

    /** Board candidates (drawn on the first open after a reset). */
    void board(Player player, QuestType kind) {
        var _ =
                q.ensureBoard(player, kind)
                        .whenComplete(
                                (board, error) -> {
                                    if (error != null || board == null) {
                                        msg().send(player, "quest.error.save");
                                        return;
                                    }
                                    showBoard(player, board);
                                });
    }

    private void showBoard(Player player, BoardState board) {
        HarborPlayer hp = api.players().get(player);
        List<QuestDef> offers = new ArrayList<>();
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (var id : board.offers()) {
            engine().def(id)
                    .ifPresent(
                            def -> {
                                offers.add(def);
                                QuestState state = engine().avail(hp, def).state();
                                String key =
                                        switch (state) {
                                            case ACTIVE, READY, COMPLETING ->
                                                    "quest.board.offer_active";
                                            case COMPLETED -> "quest.board.offer_done";
                                            case AVAILABLE -> "quest.board.offer";
                                            default -> "quest.board.offer_locked";
                                        };
                                Progress.Snapshot snap = q.preview(hp, def);
                                choices.add(
                                        new Dialogs.Choice(
                                                msg().get(
                                                                key,
                                                                Placeholder.unparsed(
                                                                        "name", def.name())),
                                                msg().get(
                                                                "quest.board.offer_reward",
                                                                Placeholder.unparsed(
                                                                        "money",
                                                                        String.valueOf(
                                                                                snap.gold())),
                                                                Placeholder.unparsed(
                                                                        "mulbineul",
                                                                        String.valueOf(
                                                                                snap.mulbineul())),
                                                                Placeholder.unparsed(
                                                                        "reputation",
                                                                        String.valueOf(
                                                                                snap
                                                                                        .reputation())))));
                            });
        }
        if (offers.isEmpty()) {
            msg().send(player, "quest.board.empty");
            return;
        }
        api.dialogs()
                .choose(
                        player,
                        msg().get("quest.board.title_" + board.kind().key()),
                        List.of(
                                msg().get(
                                                "quest.board.pick",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, board.kind()))))),
                        choices,
                        1,
                        i -> {
                            QuestDef def = offers.get(i);
                            QuestState state = engine().avail(hp, def).state();
                            if (state.open()) {
                                dialogs.detail(player, def);
                            } else if (state == QuestState.AVAILABLE) {
                                dialogs.offer(player, def);
                            } else {
                                engine().explainLocked(player, def, engine().avail(hp, def));
                            }
                        });
    }
}
