package wiki.creeper.harbor.core.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * 접속 중인 플레이어의 메모리 캐시. 메인 스레드에서만 읽고 쓴다. 바뀐 항목은 표시(dirty)되어 5분마다 지연 쓰기 큐로 저장된다.
 */
public interface HarborPlayer {

    UUID uuid();

    String name();

    PlayerRef ref();

    Instant firstJoin();

    /** 이번 접속까지 포함한 총 플레이 시간. */
    Duration playtime();

    SeasonId season();

    /** 골드·물비늘 잔액 캐시. 재화 서비스가 커밋한 뒤에만 바뀐다. 진주는 Shop 모듈에 있다. */
    long balance(Currency currency);

    int reputation();

    void setReputation(int reputation);

    Optional<JobLine> jobLine();

    JobTier jobTier();

    int level();

    long exp();

    /** 직업·레벨 갱신 (Jobs 모듈이 부른다). */
    void setJob(@Nullable JobLine line, JobTier tier, int level, long exp);

    /** 모듈 데이터. 등록되지 않은 컴포넌트면 IllegalArgumentException. */
    <T> T get(PlayerComponent<T> component);

    /** 모듈 데이터를 바꾸고 저장 대상으로 표시한다. */
    <T> void set(PlayerComponent<T> component, T value);
}
