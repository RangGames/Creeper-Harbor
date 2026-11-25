package wiki.creeper.harbor.common.pack;

import java.util.List;
import java.util.Map;
import wiki.creeper.harbor.common.redis.Channel;

/**
 * The current resource pack, announced to all servers after the building server uploads it to the
 * CDN (proposed).
 *
 * <p>Stored as JSON in Redis key {@value #KEY} and as SHA-1 only in {@value #SHA1_KEY}; on change
 * only the SHA-1 is announced on {@link #CHANNEL} (the model list is too large for messages).
 * Receiving servers read the key. The lobby sends the pack with this URL/SHA-1, and every server
 * uses the models/sounds lists to decide whether to use custom models and sounds.
 *
 * @param builtAt build time (epoch ms)
 * @param soundTicks sound event → length in ticks (Ogg files in the pack; empty in packs published
 *     before this field existed)
 */
public record PublishedPack(
        String url,
        String sha1,
        long sizeBytes,
        List<String> models,
        List<String> sounds,
        long builtAt,
        Map<String, Integer> soundTicks) {

    public static final String KEY = "harbor:pack";

    /** SHA-1 of the current pack only (servers compare it cheaply on a schedule). */
    public static final String SHA1_KEY = "harbor:pack:sha1";

    /** Notification that the pack changed. */
    public record Changed(String sha1) {}

    public static final Channel<Changed> CHANNEL = new Channel<>(KEY, Changed.class);

    public PublishedPack {
        models = List.copyOf(models);
        sounds = List.copyOf(sounds);
        // Gson passes null for packs stored before soundTicks existed
        @SuppressWarnings("NullAway")
        Map<String, Integer> ticks = soundTicks;
        soundTicks = ticks == null ? Map.of() : Map.copyOf(ticks);
    }
}
