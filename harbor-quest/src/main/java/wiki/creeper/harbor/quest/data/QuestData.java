package wiki.creeper.harbor.quest.data;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.QuestType;

/**
 * 플레이어 퀘스트 데이터 (PlayerComponent, 변경 불가, docs 20 6.3). 활성·완료 행(현재 시즌 + 모든 시즌의 메인), 게시판 후보, 추적 중인
 * 퀘스트. 쓰기는 상태 전이마다 즉시, 진행은 지연 쓰기 큐로 따로 넣으므로 컴포넌트 저장 함수는 아무것도 하지 않는다.
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
