package wiki.creeper.harbor.quest.logic;

import java.util.List;

/**
 * 추적 표시 한 줄 계산 (보스바·GUI 공용, 서버 API 없음). 지금 목표가 여러 개면(all 모드) 덜 채운 첫 목표를 보여 주고 나머지 수를 붙인다. 진행률은 지금
 * 목표들의 합 (세지 않는 목표는 0 또는 1로 친다).
 */
public final class TrackerLine {

    /** 표시 종류. */
    public enum Kind {
        ACTIVE,
        READY
    }

    /**
     * 지금 목표 하나.
     *
     * @param value 진행 값. 세지 않는 목표(납품 등)는 -1
     */
    public record Goal(String text, int value, int count) {

        boolean done() {
            return value >= 0 && value >= count;
        }
    }

    /**
     * 표시 내용.
     *
     * @param value 보여 줄 목표의 진행 값 (-1이면 숫자 없이)
     * @param more 보여 주지 않은 나머지 목표 수
     */
    public record View(
            Kind kind,
            String quest,
            String objective,
            int value,
            int count,
            int more,
            String npc,
            boolean main,
            float progress) {}

    private TrackerLine() {}

    public static View ready(String quest, String npc, boolean main) {
        return new View(Kind.READY, quest, "", -1, 0, 0, npc, main, 1f);
    }

    public static View active(String quest, List<Goal> goals, boolean main) {
        if (goals.isEmpty()) {
            return new View(Kind.ACTIVE, quest, "", -1, 0, 0, "", main, 0f);
        }
        Goal shown = goals.stream().filter(g -> !g.done()).findFirst().orElse(goals.getFirst());
        return new View(
                Kind.ACTIVE,
                quest,
                shown.text(),
                shown.value(),
                shown.count(),
                goals.size() - 1,
                "",
                main,
                progress(goals));
    }

    /** 0~1. 세는 목표는 value/count, 세지 않는 목표는 0. */
    public static float progress(List<Goal> goals) {
        if (goals.isEmpty()) {
            return 0f;
        }
        double sum = 0;
        for (Goal g : goals) {
            if (g.value() >= 0 && g.count() > 0) {
                sum += Math.min(1.0, (double) g.value() / g.count());
            }
        }
        return (float) Math.clamp(sum / goals.size(), 0.0, 1.0);
    }

    /** 칸 수만큼의 채움 칸 (0~width). GUI 진행 막대용. */
    public static int filledCells(int value, int count, int width) {
        if (count <= 0 || value <= 0) {
            return 0;
        }
        return (int) Math.min(width, Math.floor((double) value * width / count));
    }
}
