package wiki.creeper.harbor.collection.api;

import java.time.Instant;

/** One codex cell: first catch time, max size, best quality, catch count. */
public record CodexEntry(Instant firstCaught, double maxSize, int bestQuality, int count) {

    /** Value with a new catch applied (keeps larger values, first catch time unchanged). */
    public CodexEntry merge(double size, int quality) {
        return new CodexEntry(
                firstCaught, Math.max(maxSize, size), Math.max(bestQuality, quality), count + 1);
    }

    public static CodexEntry first(Instant at, double size, int quality) {
        return new CodexEntry(at, size, quality, 1);
    }
}
