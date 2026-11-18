package wiki.creeper.harbor.season.hof;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** 명예의 전당 비교·표시 (순수 계산). */
public final class HofMath {

    private HofMath() {}

    /**
     * 새 기록이 기존 1위보다 나은지. 같은 값이면 먼저 달성한 기록이 이긴다 (docs 20 11.4 동점 규칙).
     *
     * @param ascending 낮은 값이 좋은지 (처치 시간)
     */
    public static boolean better(
            boolean ascending, double value, Instant at, double oldValue, Instant oldAt) {
        int c = Double.compare(value, oldValue);
        if (c == 0) {
            return at.isBefore(oldAt);
        }
        return ascending ? c < 0 : c > 0;
    }

    /** 값 표시. unit이 {@code time_ms}면 밀리초를 분:초.소수로, 그 밖은 소수 첫째 자리까지 + 단위. */
    public static String format(double value, String unit) {
        if (unit.equals("time_ms")) {
            Duration d = Duration.ofMillis((long) value);
            return String.format(
                    Locale.ROOT,
                    "%d:%02d.%d",
                    d.toMinutes(),
                    d.toSecondsPart(),
                    d.toMillisPart() / 100);
        }
        String number =
                value == Math.rint(value)
                        ? String.valueOf((long) value)
                        : String.format(Locale.ROOT, "%.1f", value);
        return unit.isEmpty() ? number : number + unit;
    }

    /** 칭호 지급 참조 ID (docs 20 11.4: hof:&lt;season&gt;:&lt;category&gt;:&lt;subject&gt;). */
    public static String titleRef(int season, String category, String subject) {
        String ref = "hof:" + season + ":" + category + ":" + subject;
        return ref.length() > 64 ? ref.substring(0, 64) : ref;
    }
}
