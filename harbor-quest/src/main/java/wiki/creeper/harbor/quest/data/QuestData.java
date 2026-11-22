package wiki.creeper.harbor.quest.data;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.QuestType;

/**
 * Player quest data (PlayerComponent, immutable, docs 20 6.3). Active and completed rows (current
 * season + main quests of every season), board candidates, the tracked quest. Writes happen
 * immediately on each state transition and progress goes through the write-behind queue separately,
 * so the component's save function does nothing.
 */
public record QuestData(
        Map<QuestId, QuestEntry> entries,
        Map<QuestType, BoardState> boards,
        @Nullable QuestId tracked) {

    public static final QuestData EMPTY = new QuestData(Map.of(), Map.of(), null);

    public QuestData {
        entries = Map.copyOf(entries);
        boards =
                boards.isEmpty()
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new EnumMap<>(boards));
    }

    public Optional<QuestEntry> entry(QuestId quest) {
        return Optional.ofNullable(entries.get(quest));
    }

    public Optional<BoardState> board(QuestType kind) {
        return Optional.ofNullable(boards.get(kind));
    }

    public QuestData with(QuestEntry entry) {
        Map<QuestId, QuestEntry> next = new HashMap<>(entries);
        next.put(entry.quest(), entry);
        return new QuestData(next, boards, tracked);
    }

    public QuestData without(QuestId quest) {
        Map<QuestId, QuestEntry> next = new HashMap<>(entries);
        next.remove(quest);
        QuestId t = tracked != null && tracked.equals(quest) ? null : tracked;
        return new QuestData(next, boards, t);
    }

    public QuestData withBoard(BoardState board) {
        Map<QuestType, BoardState> next = new EnumMap<>(QuestType.class);
        next.putAll(boards);
        next.put(board.kind(), board);
        return new QuestData(entries, next, tracked);
    }

    public QuestData withoutBoard(QuestType kind) {
        Map<QuestType, BoardState> next = new EnumMap<>(QuestType.class);
        next.putAll(boards);
        next.remove(kind);
        return new QuestData(entries, next, tracked);
    }

    public QuestData withTracked(@Nullable QuestId quest) {
        return new QuestData(entries, boards, quest);
    }
}
