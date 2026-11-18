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
 * 제재 기간 (docs 20 1.5 {@code /hp ban <플레이어> [기간] [사유]}). 서버 API 없는 순수 로직.
 *
 * <p>형식: 숫자 + 단위를 이어 쓴다. {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w}, {@code 1d12h}. 한국어 단위
 * (초·분·시간·일·주)도 받는다. {@code perm}·{@code permanent}·{@code 영구}는 영구.
 *
 * @param length 기간. null이면 영구
 */
record SanctionTerm(@Nullable Duration length) {

    static final SanctionTerm PERMANENT = new SanctionTerm(null);

    /** 너무 긴 기간은 실수로 본다 (10년, 제안값). 영구는 perm으로 쓴다. */
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

    /** 명령 인자 해석 결과: 기간과 사유. */
    record Args(SanctionTerm term, String reason) {}

    boolean permanent() {
        return length == null;
    }

    /** 만료 시각. 영구면 null. */
    @Nullable Instant expiresAt(Instant now) {
        Duration value = length;
        return value == null ? null : now.plus(value);
    }

    /** 기간 한 토큰을 읽는다. 형식이 틀리거나 0·상한 초과면 빈 값. */
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
     * 기간을 쓰려다 틀린 것처럼 보이는지 (숫자 + 영문 단위인데 읽을 수 없음, 예: 7x, 10mo, 99999d). 사유로 넘기지 않고 오류로 알려 준다. "3회
     * 도배"처럼 한국어 사유는 해당하지 않는다.
     */
    static boolean looksLikeTerm(String token) {
        String t = token.strip();
        return LOOKS_LIKE.matcher(t).matches() && parse(t).isEmpty();
    }

    /** 나머지 인자의 첫 토큰. */
    static String firstToken(String rest) {
        String trimmed = rest.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * {@code [기간] [사유]} 나머지 인자를 나눈다. 첫 토큰이 기간 형식이면 기간, 아니면 전부 사유로 본다.
     *
     * @param rest 대상 이름 뒤의 나머지 (빈 문자열 가능)
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

    /** 사람이 읽는 기간 (예: 1일 12시간). 영구면 permanentLabel. 단위 이름은 문구 파일이 아니라 형식 기호라 코드에 둔다. */
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
