package wiki.creeper.harbor.season.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * 랭킹 리그 ('11. 데이터 설계' records.league). 기록 시점의 직업 단계로 정하고, 이후 전직해도 그 기록의 리그는 바뀌지 않는다. 키는 Collection
 * records.league 값과 같다.
 */
public enum League implements Keyed {
    TIER1,
    TIER2,
    ALL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    public static Optional<League> parse(String raw) {
        return Keyed.fromKey(League.class, raw);
    }

    /** 리그에 해당하는 직업 단계 (ALL이면 빈 Optional). */
    public Optional<JobTier> tier() {
        return switch (this) {
            case TIER1 -> Optional.of(JobTier.FIRST);
            case TIER2 -> Optional.of(JobTier.SECOND);
            case ALL -> Optional.empty();
        };
    }
}
