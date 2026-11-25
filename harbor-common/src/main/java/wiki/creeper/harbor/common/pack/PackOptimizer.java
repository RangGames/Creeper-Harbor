package wiki.creeper.harbor.common.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Lossless client pack optimizations borrowed from PackSquash (proposed). Lossy steps (PNG colour
 * quantization, audio transcoding), shader minification and non-standard zip tricks (file
 * deduplication, extraction protection) are left out on purpose: the pack must stay a plain zip
 * that any client and tool reads, with every pixel and sound unchanged.
 *
 * <ul>
 *   <li>JSON (.json, .mcmeta, OptiFine .jem/.jpm): comments and whitespace removed; in models the
 *       Blockbench-only keys {@code credit} and {@code groups} are dropped. Kept as is if it does
 *       not parse
 *   <li>PNG: {@link PngOptimizer} (metadata chunks, colour type/palette/bit depth, filters, max
 *       compression), cached by content hash so rebuilds only work on changed images
 * </ul>
 */
public final class PackOptimizer {

    /** Bump when the optimizer output changes, so old cache entries are not reused. */
    private static final String CACHE_VERSION = "1";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param enabled run the optimizations (false: files are zipped as they are)
     * @param cacheDir where optimized PNGs are kept between builds (null: no cache)
     */
    public record Options(boolean enabled, @Nullable Path cacheDir) {
        public static final Options NONE = new Options(false, null);
    }

    /** What the optimizer did in one build. */
    public record Stats(int json, int png, long bytesBefore, long bytesAfter) {
        public long saved() {
            return bytesBefore - bytesAfter;
        }
    }

    private final Options options;
    private final Set<String> usedCacheKeys = new HashSet<>();
    private int json;
    private int png;
    private long before;
    private long after;

    PackOptimizer(Options options) {
        this.options = options;
    }

    Stats stats() {
        return new Stats(json, png, before, after);
    }

    /** Optimized content for a pack path (the input when nothing applies). */
    byte[] apply(String path, byte[] data) throws IOException {
        if (!options.enabled()) {
            return data;
        }
        String ext = extension(path);
        byte[] out =
                switch (ext) {
                    case "json", "mcmeta", "jem", "jpm" -> minifyJson(path, data);
                    case "png" -> optimizePng(data);
                    default -> data;
                };
        if (out.length != data.length || !java.util.Arrays.equals(out, data)) {
            if (ext.equals("png")) {
                png++;
            } else {
                json++;
            }
            before += data.length;
            after += out.length;
        }
        return out;
    }

    /** Deletes cached PNGs this build did not use (keeps the cache the size of the pack). */
    void pruneCache() throws IOException {
        Path dir = pngCacheDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = String.valueOf(f.getFileName());
                if (name.endsWith(".png")
                        && !usedCacheKeys.contains(name.substring(0, name.length() - 4))) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ JSON

    static byte[] minifyJson(String path, byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try {
            JsonElement json = JsonParser.parseString(text);
            if (json.isJsonObject() && isModel(path)) {
                json.getAsJsonObject().remove("credit");
                json.getAsJsonObject().remove("groups");
            }
            byte[] out = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            return out.length < data.length ? out : data;
        } catch (JsonParseException | IllegalStateException e) {
            return data;
        }
    }

    /** {@code assets/<ns>/models/...json} (Blockbench exports carry credit/groups there). */
    private static boolean isModel(String path) {
        String[] parts = path.split("/", 4);
        return parts.length == 4 && parts[0].equals("assets") && parts[2].equals("models");
    }

    // ------------------------------------------------------------------ PNG

    private byte[] optimizePng(byte[] data) throws IOException {
        Path dir = pngCacheDir();
        if (dir == null) {
            return PngOptimizer.optimize(data);
        }
        String key = sha1(data);
        usedCacheKeys.add(key);
        Path cached = dir.resolve(key + ".png");
        if (Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }
        byte[] out = PngOptimizer.optimize(data);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(key + ".tmp");
        Files.write(tmp, out);
        Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    private @Nullable Path pngCacheDir() {
        Path dir = options.cacheDir();
        return dir == null ? null : dir.resolve("png-v" + CACHE_VERSION);
    }

    // ------------------------------------------------------------------ Helpers

    static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
