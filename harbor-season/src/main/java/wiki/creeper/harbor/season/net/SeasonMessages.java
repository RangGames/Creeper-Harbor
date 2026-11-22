package wiki.creeper.harbor.season.net;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.season.api.RankEntry;

/**
 * Season Redis messages (docs 20 11.2, proposed).
 *
 * <ul>
 *   <li>{@code harbor:season}: season state change notices, transition step commands
 *       (FreezeRequest) and per-server replies (FlushAck), title cache updates
 *   <li>{@code harbor:rank}: ranking aggregation results. Sent over a channel instead of the
 *       documented harbor:rank:&lt;board&gt; key and kept in memory on each server (because Core
 *       does not expose Redis key access; a freshly started server aggregates once itself)
 * </ul>
 */
public final class SeasonMessages {

    public static final String SEASON = "harbor:season";
    public static final String RANK = "harbor:rank";

    private SeasonMessages() {}

    /** season_meta (state, week shift) changed; reload it. */
    public record MetaChanged(int season) {}

    /** Transition FREEZE: kick players, drain the write-behind queue, then reply with FlushAck. */
    public record FreezeRequest(String transitionId, int season) {}

    /**
     * FREEZE reply.
     *
     * @param online players still online after kicking (including admin bypass logins)
     * @param pending entries left in the write-behind queue
     */
    public record FlushAck(
            String transitionId, String server, int online, int pending, boolean ok) {}

    /** The transition was cancelled; lift the login block (rollback A). */
    public record Thaw(String transitionId, int season) {}

    /** A title changed on another server; update the cache if the player is online. */
    public record TitleChanged(UUID player, String title, boolean granted) {}

    /** Aggregation result for one league of a ranking board. */
    public record RankingUpdate(
            String board, String league, int season, Instant computedAt, List<RankEntry> entries) {}

    public static final Channel<MetaChanged> META = new Channel<>(SEASON, MetaChanged.class);
    public static final Channel<FreezeRequest> FREEZE = new Channel<>(SEASON, FreezeRequest.class);
    public static final Channel<FlushAck> FLUSH_ACK = new Channel<>(SEASON, FlushAck.class);
    public static final Channel<Thaw> THAW = new Channel<>(SEASON, Thaw.class);
    public static final Channel<TitleChanged> TITLE = new Channel<>(SEASON, TitleChanged.class);
    public static final Channel<RankingUpdate> RANKING = new Channel<>(RANK, RankingUpdate.class);
}
