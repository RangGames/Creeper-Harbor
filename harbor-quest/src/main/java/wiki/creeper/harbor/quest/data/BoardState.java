package wiki.creeper.harbor.quest.data;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.QuestType;

/**
 * One quest_board row (immutable). chosen is the requests accepted this cycle (for daily, picking
 * one locks the rest).
 */
public record BoardState(QuestType kind, String cycle, List<QuestId> offers, List<QuestId> chosen) {

    public BoardState {
        offers = List.copyOf(offers);
        chosen = List.copyOf(chosen);
    }

    public BoardState withChosen(QuestId quest) {
        if (chosen.contains(quest)) {
            return this;
        }
        List<QuestId> next = new ArrayList<>(chosen);
        next.add(quest);
        return new BoardState(kind, cycle, offers, next);
    }
}
