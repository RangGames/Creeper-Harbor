package wiki.creeper.harbor.common.session;

import java.util.Optional;

/**
 * Session lock state. Redis value: {@code owned:<server>} or {@code handoff:<destination
 * server>:<request ID>} (docs 20 1.2).
 */
public sealed interface LockState permits LockState.Owned, LockState.Handoff {

    /** One server holds the player data. */
    record Owned(String server) implements LockState {}

    /** The source server finished saving and is handing over to the destination server. */
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
