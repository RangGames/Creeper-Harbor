package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Sanction duration (docs 20 1.5 {@code /hp ban <player> [duration] [reason]}). Pure logic without
 * server API.
 *
 * <p>Format: number + unit, concatenated. {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w}, {@code
 * 1d12h}. Korean units (초·분·시간·일·주: seconds, minutes, hours, days, weeks) are accepted too. {@code
 * perm}, {@code permanent} and {@code 영구} mean permanent.
 *
 * @param length duration. null means permanent
 */
record SanctionTerm(@Nullable Duration length) {

    static final SanctionTerm PERMANENT = new SanctionTerm(null);

    /**
     * Overly long durations are treated as mistakes (10 years, proposed value). Use perm for
     * permanent.
     */
    static final Duration MAX = Duration.ofDays(3650);

    private static final Set<String> PERMANENT_WORDS = Set.of("perm", "permanent", "영구");
    private static final Pattern PART =
            Pattern.compile("(\\d{1,6})(시간|초|분|일|주|s|m|h|d|w)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKS_LIKE = Pattern.compile("\\d+[a-zA-Z]+(\\d+[a-zA-Z]+)*");
    private static final Map<String, Duration> UNITS =
            Map.of(
                    "s", Duration.ofSeconds(1),
                    "초", Duration.ofSeconds(1),
                    "m", Duration.ofMinutes(1),
                    "분", Duration.ofMinutes(1),
                    "h", Duration.ofHours(1),
                    "시간", Duration.ofHours(1),
                    "d", Duration.ofDays(1),
                    "일", Duration.ofDays(1),
                    "w", Duration.ofDays(7),
                    "주", Duration.ofDays(7));

    /** Parsed command arguments: duration and reason. */
    record Args(SanctionTerm term, String reason) {}

    boolean permanent() {
        return length == null;
    }

    /** Expiry time. null if permanent. */
    @Nullable Instant expiresAt(Instant now) {
        Duration value = length;
        return value == null ? null : now.plus(value);
    }

    /** Reads one duration token. Empty if the format is wrong, zero, or over the cap. */
    static Optional<SanctionTerm> parse(String raw) {
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        if (PERMANENT_WORDS.contains(token)) {
            return Optional.of(PERMANENT);
        }
        Matcher matcher = PART.matcher(token);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return Optional.empty();
            }
            Duration unit = UNITS.get(matcher.group(2).toLowerCase(Locale.ROOT));
            if (unit == null) {
                return Optional.empty();
            }
            total = total.plus(unit.multipliedBy(Long.parseLong(matcher.group(1))));
            end = matcher.end();
        }
        if (end != token.length() || total.isZero() || total.compareTo(MAX) > 0) {
            return Optional.empty();
        }
        return Optional.of(new SanctionTerm(total));
    }

    /**
     * Whether it looks like a mistyped duration (number + Latin unit that cannot be parsed, e.g.
     * 7x, 10mo, 99999d). Reported as an error rather than passed as the reason. Korean reasons like
     * "3회 도배" ("spam x3") do not count.
     */
    static boolean looksLikeTerm(String token) {
        String t = token.strip();
        return LOOKS_LIKE.matcher(t).matches() && parse(t).isEmpty();
    }

    /** First token of the remaining arguments. */
    static String firstToken(String rest) {
        String trimmed = rest.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * Splits the remaining {@code [duration] [reason]} arguments. If the first token is in duration
     * format it is the duration; otherwise everything is the reason.
     *
     * @param rest the rest after the target name (may be an empty string)
     */
    static Args parseArgs(String rest, SanctionTerm defaultTerm, String defaultReason) {
        String trimmed = rest.strip();
        if (trimmed.isEmpty()) {
            return new Args(defaultTerm, defaultReason);
        }
        int space = trimmed.indexOf(' ');
        String first = space < 0 ? trimmed : trimmed.substring(0, space);
        Optional<SanctionTerm> term = parse(first);
        if (term.isEmpty()) {
            return new Args(defaultTerm, trimmed);
        }
        String reason = space < 0 ? "" : trimmed.substring(space + 1).strip();
        return new Args(term.get(), reason.isEmpty() ? defaultReason : reason);
    }

    /**
     * Human-readable duration (e.g. 1일 12시간, "1 day 12 hours"). permanentLabel if permanent. Unit
     * names are format symbols rather than message text, so they live in code.
     */
    String describe(String permanentLabel) {
        Duration value = length;
        if (value == null) {
            return permanentLabel;
        }
        StringBuilder out = new StringBuilder();
        long days = value.toDays();
        if (days > 0) {
            out.append(days).append("일 ");
        }
        if (value.toHoursPart() > 0) {
            out.append(value.toHoursPart()).append("시간 ");
        }
        if (value.toMinutesPart() > 0) {
            out.append(value.toMinutesPart()).append("분 ");
        }
        if (value.toSecondsPart() > 0) {
            out.append(value.toSecondsPart()).append("초 ");
        }
        return out.toString().strip();
    }
}
