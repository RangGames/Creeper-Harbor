package wiki.creeper.harbor.season.net;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.season.api.RankEntry;

/**
 * Season Redis 메시지 (docs 20 11.2, 추가 제안).
 *
 * <ul>
 *   <li>{@code harbor:season}: 시즌 상태 변경 알림, 전환 단계 명령(FreezeRequest)과 서버별 응답(FlushAck), 칭호 캐시 갱신
 *   <li>{@code harbor:rank}: 랭킹 집계 결과. 문서의 harbor:rank:&lt;board&gt; 키 대신 채널로 보내고 서버마다 메모리에 둔다
 *       (Core가 Redis 키 접근을 열지 않아서. 새로 켠 서버는 직접 한 번 집계한다)
 * </ul>
 */
public final class SeasonMessages {

    public static final String SEASON = "harbor:season";
    public static final String RANK = "harbor:rank";

    private SeasonMessages() {}

    /** season_meta(상태·주차 보정)가 바뀌었으니 다시 읽어라. */
    public record MetaChanged(int season) {}

    /** 전환 FREEZE: 플레이어를 내보내고 지연 쓰기 큐를 비운 뒤 FlushAck로 답하라. */
    public record FreezeRequest(String transitionId, int season) {}

    /**
     * FREEZE 응답.
     *
     * @param online 내보낸 뒤 남은 접속자 수 (관리자 우회 접속 포함)
     * @param pending 지연 쓰기 큐에 남은 건수
     */
    public record FlushAck(
            String transitionId, String server, int online, int pending, boolean ok) {}

    /** 전환을 취소했으니 접속 차단을 풀어라 (롤백 A). */
    public record Thaw(String transitionId, int season) {}

    /** 다른 서버에서 칭호가 바뀌었으니 접속 중이면 캐시를 고쳐라. */
    public record TitleChanged(UUID player, String title, boolean granted) {}

    /** 랭킹 보드 한 리그의 집계 결과. */
    public record RankingUpdate(
            String board, String league, int season, Instant computedAt, List<RankEntry> entries) {}

    public static final Channel<MetaChanged> META = new Channel<>(SEASON, MetaChanged.class);
    public static final Channel<FreezeRequest> FREEZE = new Channel<>(SEASON, FreezeRequest.class);
    public static final Channel<FlushAck> FLUSH_ACK = new Channel<>(SEASON, FlushAck.class);
    public static final Channel<Thaw> THAW = new Channel<>(SEASON, Thaw.class);
    public static final Channel<TitleChanged> TITLE = new Channel<>(SEASON, TitleChanged.class);
    public static final Channel<RankingUpdate> RANKING = new Channel<>(RANK, RankingUpdate.class);
}
