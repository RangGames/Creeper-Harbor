package wiki.creeper.harbor.proxy;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One sanction (sanctions table, docs 20 1.3).
 *
 * @param issuedBy staff UUID or CONSOLE
 * @param expiresAt expiry time. null means permanent
 */
record Sanction(
        long id,
        UUID player,
        Type type,
        String reason,
        String issuedBy,
        Instant issuedAt,
        @Nullable Instant expiresAt) {

    /** BAN: connection refused. MUTE: chat and whispers blocked. */
    enum Type {
        BAN,
        MUTE
    }

    boolean activeAt(Instant now) {
        Instant until = expiresAt;
        return until == null || now.isBefore(until);
    }
}
