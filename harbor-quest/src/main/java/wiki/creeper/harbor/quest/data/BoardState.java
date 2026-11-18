package wiki.creeper.harbor.quest.data;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.QuestType;

/** quest_board 한 행 (변경 불가). chosen은 이번 cycle에 수락한 의뢰 (일일은 1개를 고르면 나머지가 잠긴다). */
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
