package wiki.creeper.harbor.collection.api;

import java.time.Instant;
import java.util.UUID;

/** 최대어 기록 한 줄. */
public record RecordEntry(UUID player, String name, double size, Instant caughtAt) {}
