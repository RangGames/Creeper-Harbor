package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 평판 획득 내역 한 줄 (/평판 최근 내역). */
public record ReputationLogEntry(
        ReputationSource source, int delta, @Nullable String refId, Instant at) {}
