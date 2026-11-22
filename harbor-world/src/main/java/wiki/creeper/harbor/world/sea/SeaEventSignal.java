package wiki.creeper.harbor.world.sea;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;

/**
 * Sea event signal exchanged between World servers (harbor:event, proposed). Sent on the same
 * channel as Fishing's {@code SeaEventMessage} (START · END + fish_effects) in a different format
 * (filtered by the envelope's type). WARN is the announcement; START · END are start and end.
 *
 * @param runId run ID
 * @param eventId event definition ID (harbor:event/…)
 * @param region waters (null = whole server)
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
