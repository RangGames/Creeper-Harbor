package wiki.creeper.harbor.common.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lengths of the pack's sounds, so servers can restart looping music exactly when a track ends
 * instead of on a fixed interval that cuts long tracks (proposed).
 *
 * <p>An Ogg Vorbis length is the last page's granule position (samples) divided by the sample rate
 * from the Vorbis identification header; no decoding needed. A sound event's length is the longest
 * of its file entries in sounds.json.
 */
final class SoundLengths {

    private SoundLengths() {}

    /** Length of an Ogg Vorbis file in ticks (rounded up), 0 if it cannot be read. */
    static int oggTicks(byte[] ogg) {
        int rate = vorbisRate(ogg);
        long samples = lastGranule(ogg);
        if (rate <= 0 || samples <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (samples * 20 + rate - 1) / rate);
    }

    /** Sample rate from the identification header packet ("\x01vorbis"). */
    private static int vorbisRate(byte[] d) {
        for (int i = 0; i + 16 <= d.length && i < 4096; i++) {
            if (d[i] == 1
                    && d[i + 1] == 'v'
                    && d[i + 2] == 'o'
                    && d[i + 3] == 'r'
                    && d[i + 4] == 'b'
                    && d[i + 5] == 'i'
                    && d[i + 6] == 's') {
                // version (4 bytes) + channels (1 byte), then the sample rate (little endian)
                return littleInt(d, i + 12);
            }
        }
        return 0;
    }

    /** Granule position of the last Ogg page ("OggS" header, granule at byte 6). */
    private static long lastGranule(byte[] d) {
        for (int i = d.length - 27; i >= 0; i--) {
            if (d[i] == 'O' && d[i + 1] == 'g' && d[i + 2] == 'g' && d[i + 3] == 'S') {
                long g = 0;
                for (int b = 7; b >= 0; b--) {
                    g = (g << 8) | (d[i + 6 + b] & 0xff);
                }
                return g;
            }
        }
        return 0;
    }

    private static int littleInt(byte[] d, int at) {
        return (d[at] & 0xff)
                | (d[at + 1] & 0xff) << 8
                | (d[at + 2] & 0xff) << 16
                | (d[at + 3] & 0xff) << 24;
    }

    /**
     * Adds {@code ns:event → ticks} for the events in one sounds.json.
     *
     * @param fileTicks {@code ns:path} (under sounds/, without .ogg) → length ticks
     */
    static void events(
            String namespace,
            byte[] soundsJson,
            Map<String, Integer> fileTicks,
            Map<String, Integer> out) {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(soundsJson, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            return;
        }
        if (!root.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonElement sounds = e.getValue().getAsJsonObject().get("sounds");
            if (sounds == null || !sounds.isJsonArray()) {
                continue;
            }
            int longest = 0;
            for (JsonElement s : sounds.getAsJsonArray()) {
                String name;
                if (s.isJsonPrimitive()) {
                    name = s.getAsString();
                } else if (s.isJsonObject()) {
                    JsonObject o = s.getAsJsonObject();
                    JsonElement type = o.get("type");
                    if (type != null && "event".equals(type.getAsString())) {
                        continue;
                    }
                    JsonElement n = o.get("name");
                    if (n == null) {
                        continue;
                    }
                    name = n.getAsString();
                } else {
                    continue;
                }
                String key = name.contains(":") ? name : "minecraft:" + name;
                longest = Math.max(longest, fileTicks.getOrDefault(key, 0));
            }
            if (longest > 0) {
                out.put(namespace + ":" + e.getKey(), longest);
            }
        }
    }

    /** {@code assets/<ns>/sounds/<path>.ogg} → {@code ns:path}, else null. */
    static @org.jspecify.annotations.Nullable String fileKey(String path) {
        String[] parts = path.split("/", 4);
        if (parts.length < 4
                || !parts[0].equals("assets")
                || !parts[2].equals("sounds")
                || !parts[3].endsWith(".ogg")) {
            return null;
        }
        return parts[1] + ":" + parts[3].substring(0, parts[3].length() - ".ogg".length());
    }
}
