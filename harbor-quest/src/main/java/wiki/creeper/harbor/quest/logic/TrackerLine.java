package wiki.creeper.harbor.quest.logic;

import java.util.List;

/**
 * Computes one tracker line (shared by the boss bar and the GUI, no server API). With several
 * current objectives (all mode) it shows the first least-filled objective and appends the remaining
 * count. The progress rate is the sum over the current objectives (non-counting objectives count as
 * 0 or 1).
 */
public final class TrackerLine {

    /** Display kind. */
    public enum Kind {
        ACTIVE,
        READY
    }

    /**
     * One current objective.
     *
     * @param value progress value. -1 for non-counting objectives (delivery etc.)
     */
    public record Goal(String text, int value, int count) {

        boolean done() {
            return value >= 0 && value >= count;
        }
    }

    /**
     * Display content.
     *
     * @param value progress value of the shown objective (-1 means no number)
     * @param more number of remaining objectives not shown
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

    /** 0~1. value/count for counting objectives, 0 for non-counting objectives. */
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

    /** Filled cells out of width (0~width). For the GUI progress bar. */
    public static int filledCells(int value, int count, int width) {
        if (count <= 0 || value <= 0) {
            return 0;
        }
        return (int) Math.min(width, Math.floor((double) value * width / count));
    }
}
