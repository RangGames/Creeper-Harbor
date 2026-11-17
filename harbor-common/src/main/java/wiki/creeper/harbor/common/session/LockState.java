package wiki.creeper.harbor.common.session;

import java.util.Optional;

/**
 * 세션 잠금 상태. Redis 값: {@code owned:<서버>} 또는 {@code handoff:<도착 서버>:<요청 ID>} (docs 20 1.2).
 */
public sealed interface LockState permits LockState.Owned, LockState.Handoff {

    /** 서버 하나가 플레이어 데이터를 가지고 있다. */
    record Owned(String server) implements LockState {}

    /** 출발 서버가 저장을 마치고 도착 서버에 넘기는 중. */
    record Handoff(String to, String requestId) implements LockState {}

    static String encode(LockState state) {
        return switch (state) {
            case Owned(String server) -> "owned:" + server;
            case Handoff(String to, String requestId) -> "handoff:" + to + ":" + requestId;
        };
    }

    static Optional<LockState> decode(String raw) {
        String[] parts = raw.split(":", -1);
        if (parts.length == 2 && parts[0].equals("owned")) {
            return Optional.of(new Owned(parts[1]));
        }
        if (parts.length == 3 && parts[0].equals("handoff")) {
            return Optional.of(new Handoff(parts[1], parts[2]));
        }
        return Optional.empty();
    }
}
