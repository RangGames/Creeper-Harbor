package wiki.creeper.harbor.proxy;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 제재 한 건 (sanctions 테이블, docs 20 1.3).
 *
 * @param issuedBy 운영진 UUID 또는 CONSOLE
 * @param expiresAt 만료 시각. null이면 영구
 */
record Sanction(
        long id,
        UUID player,
        Type type,
        String reason,
        String issuedBy,
        Instant issuedAt,
        @Nullable Instant expiresAt) {

    /** BAN: 접속 거부. MUTE: 채팅·귓속말 차단. */
    enum Type {
        BAN,
        MUTE
    }

    boolean activeAt(Instant now) {
        Instant until = expiresAt;
        return until == null || now.isBefore(until);
    }
}
