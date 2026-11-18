package wiki.creeper.harbor.fishing.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/**
 * 등급별 미니게임 성공률·평균 시간 (docs 20 3.4 '목표 성공률 확인', /harbor fishing stats). 최근 24시간만 메모리에 둔다 (서버별, 지연 쓰기
 * 대상인 통계 테이블은 다음 단계). 메인 스레드 전용, 서버 API 없음.
 */
public final class MinigameStats {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_ENTRIES = 50_000;

    private record Entry(Instant at, Grade grade, MinigameResult result, int ticks) {}

    /** 한 등급의 요약. */
    public record Summary(int attempts, int caught, double averageSeconds) {
        public double successRate() {
            return attempts == 0 ? 0 : (double) caught / attempts;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    public void record(Grade grade, MinigameResult result, int ticks, Instant now) {
        if (result == MinigameResult.ABORTED) {
            return;
        }
        entries.addLast(new Entry(now, grade, result, ticks));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        trim(now);
    }

    public Map<Grade, Summary> summary(Instant now) {
        trim(now);
        Map<Grade, int[]> counts = new EnumMap<>(Grade.class);
        for (Entry e : entries) {
            int[] c = counts.computeIfAbsent(e.grade(), g -> new int[3]);
            c[0]++;
            if (e.result() == MinigameResult.CAUGHT) {
                c[1]++;
            }
            c[2] += e.ticks();
        }
        Map<Grade, Summary> result = new EnumMap<>(Grade.class);
        counts.forEach(
                (g, c) ->
                        result.put(g, new Summary(c[0], c[1], c[0] == 0 ? 0 : c[2] / 20.0 / c[0])));
        return result;
    }

    private void trim(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!entries.isEmpty() && entries.peekFirst().at().isBefore(cutoff)) {
            entries.removeFirst();
        }
    }
}
