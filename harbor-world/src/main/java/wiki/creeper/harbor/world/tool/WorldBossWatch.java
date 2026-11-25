package wiki.creeper.harbor.world.tool;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Latest world boss progress for the telescope. World does not depend on Boss (docs 20 0.3), so it
 * listens to the harbor:worldboss broadcast (every 2 seconds) with a record of the same shape; the
 * message bus matches the record by simple name.
 */
public final class WorldBossWatch {

    /**
     * Same fields as the Boss broadcast payload (Boss WorldBossManager.WorldBossProgress). The
     * simple name must stay WorldBossProgress.
     */
    public record WorldBossProgress(
            String boss,
            String eventId,
            int stage,
            double percent,
            int participants,
            String state,
            String server,
            String name) {}

    /** What the telescope shows. */
    public record Progress(String name, double percent, int participants) {}

    private record Seen(WorldBossProgress progress, long atMillis) {}

    private static final Channel<WorldBossProgress> CHANNEL =
            new Channel<>(Channels.WORLD_BOSS, WorldBossProgress.class);

    private volatile @Nullable Seen latest;

    public void start(HarborApi api) {
        api.bus()
                .subscribe(
                        CHANNEL,
                        envelope ->
                                latest = new Seen(envelope.payload(), System.currentTimeMillis()));
    }

    /** The running world boss if a broadcast arrived within maxAgeMillis and it has not ended. */
    public Optional<Progress> latest(long maxAgeMillis) {
        Seen seen = latest;
        if (seen == null || System.currentTimeMillis() - seen.atMillis() > maxAgeMillis) {
            return Optional.empty();
        }
        WorldBossProgress p = seen.progress();
        if (p.stage() >= 4) {
            return Optional.empty();
        }
        return Optional.of(new Progress(p.name(), p.percent(), p.participants()));
    }
}
