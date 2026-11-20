package wiki.creeper.harbor.economy.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 시세판 추세 계산 (추가 제안, 서버 API 없음). 24시간 전 계수와 지금 계수를 비교한다. */
public final class MarketTrend {

    /** 방향. */
    public enum Direction {
        UP,
        DOWN,
        FLAT
    }

    private MarketTrend() {}

    /** 지금 - 24시간 전. 이전 값이 없으면 0. */
    public static double change(double now, double before) {
        return Double.isNaN(before) ? 0 : now - before;
    }

    /**
     * @param flat 이 변화량 이하(절댓값)는 보합
     */
    public static Direction direction(double change, double flat) {
        if (change > flat) {
            return Direction.UP;
        }
        if (change < -flat) {
            return Direction.DOWN;
        }
        return Direction.FLAT;
    }

    /** 변화가 큰 순서로 n개 (보합 제외). */
    public static Set<String> topMovers(Map<String, Double> changes, int n, double flat) {
        return changes.entrySet().stream()
                .filter(e -> Math.abs(e.getValue()) > flat)
                .sorted(
                        Comparator.comparingDouble(
                                        (Map.Entry<String, Double> e) -> -Math.abs(e.getValue()))
                                .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, n))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 24시간 기록(오래된 순)의 첫 값. 없으면 NaN. */
    public static double oldest(List<Double> history) {
        return history.isEmpty() ? Double.NaN : history.getFirst();
    }
}
