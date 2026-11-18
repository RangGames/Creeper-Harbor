package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 명예의 전당 한 줄 (hall_of_fame 행).
 *
 * @param subject 대상 ID 문자열 (어종·보스 ID, 대상이 없으면 "-")
 * @param finalized 시즌 마감으로 확정됐는지 (false면 진행 중 잠정 기록)
 */
public record HallOfFameEntry(
        int season,
        String category,
        String subject,
        int rank,
        UUID player,
        String nameAtTime,
        double value,
        Map<String, String> detail,
        Instant recordedAt,
        boolean finalized) {

    /** 대상이 없는 분류의 subject 값. */
    public static final String NO_SUBJECT = "-";

    public HallOfFameEntry {
        detail = Map.copyOf(detail);
    }
}
