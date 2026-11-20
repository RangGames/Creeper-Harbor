package wiki.creeper.harbor.collection.view;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import wiki.creeper.harbor.collection.api.RecordEntry;

/** 최대어 순위 계산 (추가 제안, 서버 API 없음). 서버 기록 캐시는 리그별 상위 10이고 크기 내림차순·먼저 잡은 사람 우선이다. */
public final class RecordRanks {

    private RecordRanks() {}

    /** 상위 목록 안에서 그 플레이어의 순위 (1부터, 없으면 비어 있음). */
    public static OptionalInt rank(List<RecordEntry> top, UUID player) {
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).player().equals(player)) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 상위 목록에 들려면 더 키워야 하는 크기 (cm). 목록이 차지 않았으면 0, 이미 들어 있거나 넘으면 0.
     *
     * @param capacity 목록 크기 (서버 캐시 10)
     */
    public static OptionalDouble gapToTop(List<RecordEntry> top, double mySize, int capacity) {
        if (top.size() < capacity) {
            return OptionalDouble.of(0);
        }
        double last = top.getLast().size();
        return OptionalDouble.of(Math.max(0, Math.nextUp(last) - mySize));
    }

    /** 이 크기라면 몇 위가 되는지 (같은 크기는 먼저 잡은 사람 우선이라 그 뒤). capacity를 넘으면 비어 있음. */
    public static OptionalInt wouldRank(List<RecordEntry> top, double size, int capacity) {
        int position = 1;
        for (RecordEntry e : top) {
            if (e.size() >= size) {
                position++;
            }
        }
        return position <= capacity ? OptionalInt.of(position) : OptionalInt.empty();
    }
}
