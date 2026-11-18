package wiki.creeper.harbor.world.sea;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.content.SeaEventDef;

/**
 * 해상 이벤트 일정 계산 (docs 20 8.4 '해상 이벤트 스케줄러' 1·2단계). 날짜와 이벤트 ID로 정한 난수(FNV-1a)를 써서, 어느 서버에서 언제 계산해도 같은
 * 결과가 나온다: 그날 발동할지(chance), 창 안의 시작 시각, 후보 지역 중 어디인지. 잠금을 가진 서버가 바뀌어도 일정이 흔들리지 않는다. 서버 API 없음 (단위
 * 테스트).
 */
public final class EventPlanner {

    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private EventPlanner() {}

    /**
     * 실행 한 번의 계획.
     *
     * @param runId 실행 ID (<이벤트 이름>_<시작 시각 yyyyMMddHHmm>), Fishing 신화 카운터 키에도 쓰인다
     * @param region 해역 (null = 서버 전체)
     */
    public record Plan(
            String runId,
            SeaEventDef def,
            @Nullable RegionId region,
            Instant warnAt,
            Instant startAt,
            Instant endAt) {

        public boolean isOpen(Instant now) {
            return !now.isBefore(warnAt) && now.isBefore(endAt);
        }
    }

    /** 이 날짜에 창이 열리는 실행 (없으면 빈 값). 확률·요일만 본다 (최소 간격·플래그·시즌 횟수는 {@link #allowed}). */
    public static Optional<Plan> plan(SeaEventDef def, LocalDate date, ZoneId zone) {
        SeaEventDef.Window window = def.window();
        if (!window.days().contains(date.getDayOfWeek())) {
            return Optional.empty();
        }
        String seed = def.id() + "@" + date;
        if (unit(seed + "#roll") >= def.chance()) {
            return Optional.empty();
        }
        int windowMinutes = window.minutes();
        int maxOffset =
                def.durationMinutes() < windowMinutes ? windowMinutes - def.durationMinutes() : 0;
        int offset = (int) Math.floor(unit(seed + "#start") * (maxOffset + 1));
        ZonedDateTime start = date.atTime(window.start()).atZone(zone).plusMinutes(offset);
        RegionId region = null;
        if (!def.regions().isEmpty()) {
            int pick =
                    (int) Math.floor(unit(seed + "#region") * def.regions().size())
                            % def.regions().size();
            region = def.regions().get(pick);
        }
        Instant startAt = start.toInstant();
        return Optional.of(
                new Plan(
                        def.key() + "_" + RUN.format(start),
                        def,
                        region,
                        startAt.minus(Duration.ofMinutes(def.warnMinutes())),
                        startAt,
                        startAt.plus(Duration.ofMinutes(def.durationMinutes()))));
    }

    /** 지금 열려 있어야 하는 실행 (어제 창이 자정을 넘어 이어지는 경우 포함). */
    public static List<Plan> openPlans(SeaEventDef def, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Plan> result = new ArrayList<>();
        for (LocalDate d : List.of(today.minusDays(1), today, today.plusDays(1))) {
            plan(def, d, zone).filter(p -> p.isOpen(now)).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 최소 간격·플래그·시즌 횟수 검사.
     *
     * @param lastStart 같은 이벤트의 앞 실행 시작 (없으면 null)
     * @param seasonRuns 이번 시즌 실행 수
     * @param flagSet requires_flag가 서 있는지 (조건이 없으면 true)
     */
    public static boolean allowed(
            Plan plan, @Nullable Instant lastStart, int seasonRuns, boolean flagSet) {
        SeaEventDef def = plan.def();
        if (!flagSet) {
            return false;
        }
        if (def.maxPerSeason() > 0 && seasonRuns >= def.maxPerSeason()) {
            return false;
        }
        return lastStart == null
                || !plan.startAt().isBefore(lastStart.plus(Duration.ofHours(def.minGapHours())));
    }

    /** 문자열 → [0, 1) (FNV-1a 64비트 + 섞기). */
    static double unit(String seed) {
        long h = 0xcbf29ce484222325L;
        for (byte b : seed.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
