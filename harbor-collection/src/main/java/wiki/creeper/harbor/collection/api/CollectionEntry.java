package wiki.creeper.harbor.collection.api;

import java.time.Instant;

/** One collection cell: first catch time, max size, best quality, catch count. */
public record CollectionEntry(Instant firstCaught, double maxSize, int bestQuality, int count) {

    /** Value with a new catch applied (keeps larger values, first catch time unchanged). */
    public CollectionEntry merge(double size, int quality) {
        return new CollectionEntry(
                firstCaught, Math.max(maxSize, size), Math.max(bestQuality, quality), count + 1);
    }

    public static CollectionEntry first(Instant at, double size, int quality) {
        return new CollectionEntry(at, size, quality, 1);
    }
}
