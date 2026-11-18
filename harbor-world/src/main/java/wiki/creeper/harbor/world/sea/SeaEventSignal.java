package wiki.creeper.harbor.world.sea;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;

/**
 * World 서버끼리 주고받는 해상 이벤트 신호 (harbor:event, 추가 제안). Fishing용 {@code SeaEventMessage}(START·END +
 * fish_effects)와 같은 채널에 다른 형식으로 보낸다 (봉투의 type으로 거른다). WARN은 예고, START·END는 시작·종료.
 *
 * @param runId 실행 ID
 * @param eventId 이벤트 정의 ID (harbor:event/…)
 * @param region 해역 (null = 서버 전체)
 */
public record SeaEventSignal(
        String runId,
        String eventId,
        String state,
        @Nullable String region,
        Instant warnAt,
        Instant startsAt,
        Instant endsAt) {

    public static final Channel<SeaEventSignal> CHANNEL =
            new Channel<>(Channels.EVENT, SeaEventSignal.class);

    public static final String WARN = "WARN";
    public static final String START = "START";
    public static final String END = "END";
    public static final String CANCEL = "CANCEL";
}
