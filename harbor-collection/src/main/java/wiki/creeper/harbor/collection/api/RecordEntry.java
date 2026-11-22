package wiki.creeper.harbor.collection.api;

import java.time.Instant;
import java.util.UUID;

/** One biggest-catch record row. */
public record RecordEntry(UUID player, String name, double size, Instant caughtAt) {}
