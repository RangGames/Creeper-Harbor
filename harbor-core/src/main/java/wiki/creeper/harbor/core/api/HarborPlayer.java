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
 * In-memory cache of an online player. Read and written only on the main thread. Changed entries
 * are marked dirty and saved to the write-behind queue every 5 minutes.
 */
public interface HarborPlayer {

    UUID uuid();

    String name();

    PlayerRef ref();

    Instant firstJoin();

    /** Total play time including the current session. */
    Duration playtime();

    SeasonId season();

    /**
     * Cached gold and sea scale balances. Changed only after the currency service commits. Pearls
     * live in the Shop module.
     */
    long balance(Currency currency);

    int reputation();

    void setReputation(int reputation);

    Optional<JobLine> jobLine();

    JobTier jobTier();

    int level();

    long exp();

    /** Updates job and level (called by the Jobs module). */
    void setJob(@Nullable JobLine line, JobTier tier, int level, long exp);

    /** Module data. IllegalArgumentException if the component is not registered. */
    <T> T get(PlayerComponent<T> component);

    /** Replaces module data and marks it for saving. */
    <T> void set(PlayerComponent<T> component, T value);
}
