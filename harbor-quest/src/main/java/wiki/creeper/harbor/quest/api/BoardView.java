package wiki.creeper.harbor.quest.api;

import java.util.List;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * One player's request board of the current cycle (fishermen's guild board).
 *
 * @param kind board kind key (daily · weekly · collect)
 * @param cycle cycle key the board was drawn for
 * @param offers requests on the board, in board order
 * @param chosen requests already accepted this cycle
 * @param remainingPicks how many more requests can be accepted this cycle
 */
public record BoardView(
        String kind, String cycle, List<QuestId> offers, List<QuestId> chosen, int remainingPicks) {

    public BoardView {
        offers = List.copyOf(offers);
        chosen = List.copyOf(chosen);
    }
}
