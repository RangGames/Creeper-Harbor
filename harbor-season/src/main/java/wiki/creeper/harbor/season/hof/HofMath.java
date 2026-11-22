package wiki.creeper.harbor.season.hof;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Hall of fame comparison and display (pure computation). */
public final class HofMath {

    private HofMath() {}

    /**
     * Whether a new record beats the current top. On equal values, the record achieved first wins
     * (docs 20 11.4 tie rule).
     *
     * @param ascending whether lower values are better (kill time)
     */
    public static boolean better(
            boolean ascending, double value, Instant at, double oldValue, Instant oldAt) {
        int c = Double.compare(value, oldValue);
        if (c == 0) {
            return at.isBefore(oldAt);
        }
        return ascending ? c < 0 : c > 0;
    }

    /**
     * Value display. If unit is {@code time_ms}, milliseconds as min:sec.fraction; otherwise one
     * decimal place + unit.
     */
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

    /**
     * Title grant reference ID (docs 20 11.4: hof:&lt;season&gt;:&lt;category&gt;:&lt;subject&gt;).
     */
    public static String titleRef(int season, String category, String subject) {
        String ref = "hof:" + season + ":" + category + ":" + subject;
        return ref.length() > 64 ? ref.substring(0, 64) : ref;
    }
}
