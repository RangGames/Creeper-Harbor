package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One reputation gain entry (recent history of the reputation command). */
public record ReputationLogEntry(
        ReputationSource source, int delta, @Nullable String refId, Instant at) {}
