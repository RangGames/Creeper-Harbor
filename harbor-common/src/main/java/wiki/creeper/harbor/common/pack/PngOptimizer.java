package wiki.creeper.harbor.common.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * Lossless PNG size reduction for the client pack (the lossless part of PackSquash's PNG handling,
 * proposed). Every pixel keeps its exact RGBA value, including the colour of fully transparent
 * pixels (shaders and emissive tricks read them).
 *
 * <ul>
 *   <li>Drops ancillary chunks Minecraft ignores (text, time, physical size, colour management…)
 *   <li>Re-encodes 8-bit and lower, non-interlaced images: picks the smallest colour type (palette
 *       with 1/2/4/8-bit depth, grey, grey+alpha, RGB, RGBA) that holds the exact pixels, tries
 *       per-row filter choices, compresses at the maximum level
 *   <li>16-bit, interlaced and animated (APNG) images only lose ancillary chunks
 *   <li>The result is used only when it is smaller; anything unreadable is returned as is
 * </ul>
 *
 * Deterministic: the same input always gives the same bytes (stable pack SHA-1).
 */
final class PngOptimizer {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** Ancillary chunks kept (tRNS carries transparency). Everything else ancillary is dropped. */
    private static final String KEEP_ANCILLARY = "tRNS";

    /** Re-encoding is skipped above this many pixels (memory bound, 4096×4096). */
    private static final long MAX_PIXELS = 4096L * 4096L;

    private PngOptimizer() {}

    private static final class Chunk {
        private final String type;
        private final byte[] data;

        Chunk(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        String type() {
            return type;
        }

        byte[] data() {
            return data;
        }
    }

    /** Smaller lossless PNG, or the input when it cannot be improved or read. */
    static byte[] optimize(byte[] png) {
        List<Chunk> chunks = chunks(png);
        if (chunks == null || chunks.isEmpty() || !chunks.getFirst().type().equals("IHDR")) {
            return png;
        }
        boolean animated = chunks.stream().anyMatch(c -> c.type().equals("acTL"));
        if (animated) {
            return png;
        }
        byte[] stripped = write(strip(chunks));
        byte[] best = stripped.length < png.length ? stripped : png;
        try {
            byte[] reencoded = reencode(chunks);
            if (reencoded != null && reencoded.length < best.length) {
                best = reencoded;
            }
        } catch (RuntimeException | DataFormatException e) {
            // Unusual or broken image: keep the stripped/original bytes
        }
        return best;
    }

    // ------------------------------------------------------------------ Chunks

    private static @Nullable List<Chunk> chunks(byte[] png) {
        if (png.length < 8 || !Arrays.equals(Arrays.copyOf(png, 8), SIGNATURE)) {
            return null;
        }
        List<Chunk> out = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(png, 8, png.length - 8);
        while (buf.remaining() >= 12) {
            int length = buf.getInt();
            if (length < 0 || length > buf.remaining() - 8) {
                return null;
            }
            byte[] typeBytes = new byte[4];
            buf.get(typeBytes);
            byte[] data = new byte[length];
            buf.get(data);
            buf.getInt(); // CRC (rewritten on output)
            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
            out.add(new Chunk(type, data));
            if (type.equals("IEND")) {
                return out;
            }
        }
        return null;
    }

    /** Critical chunks + tRNS, consecutive IDAT chunks merged into one. */
    private static List<Chunk> strip(List<Chunk> chunks) {
        List<Chunk> out = new ArrayList<>();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean idatAdded = false;
        for (Chunk c : chunks) {
            boolean critical = Character.isUpperCase(c.type().charAt(0));
            if (c.type().equals("IDAT")) {
                idat.writeBytes(c.data());
                continue;
            }
            if (!idatAdded && idat.size() > 0) {
                out.add(new Chunk("IDAT", idat.toByteArray()));
                idatAdded = true;
            }
            if (critical || c.type().equals(KEEP_ANCILLARY)) {
                out.add(c);
            }
        }
        return out;
    }

    private static byte[] write(List<Chunk> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        for (Chunk c : chunks) {
            byte[] type = c.type().getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(ByteBuffer.allocate(4).putInt(c.data().length).array());
            out.writeBytes(type);
            out.writeBytes(c.data());
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(c.data());
            out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ Decode

    /** Decoded image as non-premultiplied RGBA (0xRRGGBBAA per pixel). */
    private static final class Image {
        private final int width;
        private final int height;
        private final int[] rgba;

        Image(int width, int height, int[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        int[] rgba() {
            return rgba;
        }
    }

    private static byte @Nullable [] reencode(List<Chunk> chunks) throws DataFormatException {
        Image image = decode(chunks);
        if (image == null) {
            return null;
        }
        return encode(image);
    }

    private static @Nullable Image decode(List<Chunk> chunks) throws DataFormatException {
        ByteBuffer ihdr = ByteBuffer.wrap(chunks.getFirst().data());
        int width = ihdr.getInt();
        int height = ihdr.getInt();
        int depth = ihdr.get() & 0xff;
        int colorType = ihdr.get() & 0xff;
        int compression = ihdr.get() & 0xff;
        int filter = ihdr.get() & 0xff;
        int interlace = ihdr.get() & 0xff;
        if (width <= 0
                || height <= 0
                || (long) width * height > MAX_PIXELS
                || depth > 8
                || compression != 0
                || filter != 0
                || interlace != 0) {
            return null;
        }
        int channels =
                switch (colorType) {
                    case 0, 3 -> 1;
                    case 2 -> 3;
                    case 4 -> 2;
                    case 6 -> 4;
                    default -> -1;
                };
        if (channels < 0) {
            return null;
        }
        byte[] palette = null;
        byte[] trns = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        for (Chunk c : chunks) {
            switch (c.type()) {
                case "PLTE" -> palette = c.data();
                case "tRNS" -> trns = c.data();
                case "IDAT" -> idat.writeBytes(c.data());
                default -> {}
            }
        }
        if (colorType == 3 && palette == null) {
            return null;
        }
        int bitsPerPixel = depth * channels;
        int stride = (width * bitsPerPixel + 7) / 8;
        int bpp = Math.max(1, bitsPerPixel / 8);
        byte[] raw = inflate(idat.toByteArray(), (stride + 1) * height);
        if (raw == null) {
            return null;
        }
        byte[] prev = new byte[stride];
        byte[] cur = new byte[stride];
        int[] rgba = new int[width * height];
        for (int y = 0; y < height; y++) {
            int off = y * (stride + 1);
            int type = raw[off] & 0xff;
            System.arraycopy(raw, off + 1, cur, 0, stride);
            unfilter(type, cur, prev, bpp);
            for (int x = 0; x < width; x++) {
                rgba[y * width + x] = pixel(cur, x, depth, colorType, palette, trns);
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return new Image(width, height, rgba);
    }

    private static byte @Nullable [] inflate(byte[] data, int expected) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int read = inflater.inflate(out, n, expected - n);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += read;
            }
            return n == expected ? out : null;
        } finally {
            inflater.end();
        }
    }

    private static void unfilter(int type, byte[] cur, byte[] prev, int bpp) {
        for (int i = 0; i < cur.length; i++) {
            int a = i >= bpp ? cur[i - bpp] & 0xff : 0;
            int b = prev[i] & 0xff;
            int c = i >= bpp ? prev[i - bpp] & 0xff : 0;
            int x = cur[i] & 0xff;
            int v =
                    switch (type) {
                        case 0 -> x;
                        case 1 -> x + a;
                        case 2 -> x + b;
                        case 3 -> x + ((a + b) >>> 1);
                        case 4 -> x + paeth(a, b, c);
                        default -> throw new IllegalStateException("bad filter " + type);
                    };
            cur[i] = (byte) v;
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static int sample(byte[] row, int index, int depth) {
        if (depth == 8) {
            return row[index] & 0xff;
        }
        int bit = index * depth;
        int shift = 8 - depth - (bit & 7);
        return (row[bit >> 3] >> shift) & ((1 << depth) - 1);
    }

    private static int pixel(
            byte[] row,
            int x,
            int depth,
            int colorType,
            byte @Nullable [] palette,
            byte @Nullable [] trns) {
        int scale = depth == 8 ? 1 : 255 / ((1 << depth) - 1);
        return switch (colorType) {
            case 0 -> {
                int raw = sample(row, x, depth);
                int g = raw * scale;
                int a = 255;
                if (trns != null && trns.length >= 2) {
                    int key = ((trns[0] & 0xff) << 8) | (trns[1] & 0xff);
                    if (key == raw) {
                        a = 0;
                    }
                }
                yield rgba(g, g, g, a);
            }
            case 3 -> {
                int i = sample(row, x, depth);
                byte[] p = java.util.Objects.requireNonNull(palette);
                if (i * 3 + 2 >= p.length) {
                    throw new IllegalStateException("palette index out of range");
                }
                int a = trns != null && i < trns.length ? trns[i] & 0xff : 255;
                yield rgba(p[i * 3] & 0xff, p[i * 3 + 1] & 0xff, p[i * 3 + 2] & 0xff, a);
            }
            case 2 -> {
                int r = row[x * 3] & 0xff;
                int g = row[x * 3 + 1] & 0xff;
                int b = row[x * 3 + 2] & 0xff;
                int a = 255;
                if (trns != null && trns.length >= 6) {
                    if (r == (trns[1] & 0xff) && g == (trns[3] & 0xff) && b == (trns[5] & 0xff)) {
                        a = 0;
                    }
                }
                yield rgba(r, g, b, a);
            }
            case 4 -> {
                int g = row[x * 2] & 0xff;
                yield rgba(g, g, g, row[x * 2 + 1] & 0xff);
            }
            default ->
                    rgba(
                            row[x * 4] & 0xff,
                            row[x * 4 + 1] & 0xff,
                            row[x * 4 + 2] & 0xff,
                            row[x * 4 + 3] & 0xff);
        };
    }

    private static int rgba(int r, int g, int b, int a) {
        return (r << 24) | (g << 16) | (b << 8) | a;
    }

    // ------------------------------------------------------------------ Encode

    private static byte[] encode(Image img) {
        int[] px = img.rgba();
        boolean opaque = true;
        boolean grey = true;
        Map<Integer, Integer> colors = new LinkedHashMap<>();
        for (int p : px) {
            int r = p >>> 24;
            int g = (p >>> 16) & 0xff;
            int b = (p >>> 8) & 0xff;
            int a = p & 0xff;
            opaque &= a == 255;
            grey &= r == g && g == b;
            if (colors.size() <= 256) {
                colors.merge(p, 1, Integer::sum);
            }
        }
        List<byte[]> candidates = new ArrayList<>();
        if (colors.size() <= 256) {
            candidates.add(encodePalette(img, colors.keySet()));
        }
        if (grey) {
            candidates.add(encodeDirect(img, opaque ? 0 : 4));
        }
        candidates.add(encodeDirect(img, opaque ? 2 : 6));
        byte[] best = candidates.getFirst();
        for (byte[] c : candidates) {
            if (c.length < best.length) {
                best = c;
            }
        }
        return best;
    }

    private static byte[] encodePalette(Image img, java.util.Set<Integer> colorSet) {
        // Translucent entries first so tRNS can stop early; then by value (deterministic)
        Integer[] sorted = colorSet.toArray(new Integer[0]);
        Arrays.sort(
                sorted,
                (x, y) -> {
                    boolean xo = (x & 0xff) == 255;
                    boolean yo = (y & 0xff) == 255;
                    if (xo != yo) {
                        return xo ? 1 : -1;
                    }
                    return Integer.compareUnsigned(x, y);
                });
        Map<Integer, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < sorted.length; i++) {
            index.put(sorted[i], i);
        }
        int n = sorted.length;
        int depth = n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : 8;
        byte[] plte = new byte[n * 3];
        int translucent = 0;
        for (int i = 0; i < n; i++) {
            int c = sorted[i];
            plte[i * 3] = (byte) (c >>> 24);
            plte[i * 3 + 1] = (byte) (c >>> 16);
            plte[i * 3 + 2] = (byte) (c >>> 8);
            if ((c & 0xff) != 255) {
                translucent = i + 1;
            }
        }
        int stride = (img.width() * depth + 7) / 8;
        byte[][] rows = new byte[img.height()][stride];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                int i = index.getOrDefault(img.rgba()[y * img.width() + x], 0);
                int bit = x * depth;
                rows[y][bit >> 3] |= (byte) (i << (8 - depth - (bit & 7)));
            }
        }
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(new Chunk("IHDR", ihdr(img, depth, 3)));
        chunks.add(new Chunk("PLTE", plte));
        if (translucent > 0) {
            byte[] trns = new byte[translucent];
            for (int i = 0; i < translucent; i++) {
                trns[i] = (byte) (sorted[i] & 0xff);
            }
            chunks.add(new Chunk("tRNS", trns));
        }
        // Palette data usually compresses best unfiltered; also try adaptive filtering
        chunks.add(new Chunk("IDAT", bestIdat(rows, 1)));
        chunks.add(new Chunk("IEND", new byte[0]));
        return write(chunks);
    }

    private static byte[] encodeDirect(Image img, int colorType) {
        int channels =
                switch (colorType) {
                    case 0 -> 1;
                    case 4 -> 2;
                    case 2 -> 3;
                    default -> 4;
                };
        int stride = img.width() * channels;
        byte[][] rows = new byte[img.height()][stride];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                int p = img.rgba()[y * img.width() + x];
                int o = x * channels;
                switch (colorType) {
                    case 0 -> rows[y][o] = (byte) (p >>> 24);
                    case 4 -> {
                        rows[y][o] = (byte) (p >>> 24);
                        rows[y][o + 1] = (byte) p;
                    }
                    case 2 -> {
                        rows[y][o] = (byte) (p >>> 24);
                        rows[y][o + 1] = (byte) (p >>> 16);
                        rows[y][o + 2] = (byte) (p >>> 8);
                    }
                    default -> {
                        rows[y][o] = (byte) (p >>> 24);
                        rows[y][o + 1] = (byte) (p >>> 16);
                        rows[y][o + 2] = (byte) (p >>> 8);
                        rows[y][o + 3] = (byte) p;
                    }
                }
            }
        }
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(new Chunk("IHDR", ihdr(img, 8, colorType)));
        chunks.add(new Chunk("IDAT", bestIdat(rows, channels)));
        chunks.add(new Chunk("IEND", new byte[0]));
        return write(chunks);
    }

    private static byte[] ihdr(Image img, int depth, int colorType) {
        return ByteBuffer.allocate(13)
                .putInt(img.width())
                .putInt(img.height())
                .put((byte) depth)
                .put((byte) colorType)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
    }

    /** Smallest of: all rows unfiltered, and per-row minimum-sum-of-absolute-differences. */
    private static byte[] bestIdat(byte[][] rows, int bpp) {
        byte[] none = deflate(filtered(rows, bpp, false));
        byte[] adaptive = deflate(filtered(rows, bpp, true));
        return adaptive.length < none.length ? adaptive : none;
    }

    private static byte[] filtered(byte[][] rows, int bpp, boolean adaptive) {
        int stride = rows.length == 0 ? 0 : rows[0].length;
        byte[] out = new byte[rows.length * (stride + 1)];
        byte[] prev = new byte[stride];
        byte[] trial = new byte[stride];
        byte[] best = new byte[stride];
        for (int y = 0; y < rows.length; y++) {
            byte[] cur = rows[y];
            int bestType = 0;
            System.arraycopy(cur, 0, best, 0, stride);
            if (adaptive) {
                long bestScore = score(cur);
                for (int type = 1; type <= 4; type++) {
                    filterRow(type, cur, prev, bpp, trial);
                    long s = score(trial);
                    if (s < bestScore) {
                        bestScore = s;
                        bestType = type;
                        System.arraycopy(trial, 0, best, 0, stride);
                    }
                }
            }
            int off = y * (stride + 1);
            out[off] = (byte) bestType;
            System.arraycopy(best, 0, out, off + 1, stride);
            prev = cur;
        }
        return out;
    }

    private static void filterRow(int type, byte[] cur, byte[] prev, int bpp, byte[] out) {
        for (int i = 0; i < cur.length; i++) {
            int x = cur[i] & 0xff;
            int a = i >= bpp ? cur[i - bpp] & 0xff : 0;
            int b = prev[i] & 0xff;
            int c = i >= bpp ? prev[i - bpp] & 0xff : 0;
            int v =
                    switch (type) {
                        case 1 -> x - a;
                        case 2 -> x - b;
                        case 3 -> x - ((a + b) >>> 1);
                        default -> x - paeth(a, b, c);
                    };
            out[i] = (byte) v;
        }
    }

    /** Sum of absolute values as signed bytes (standard heuristic). */
    private static long score(byte[] row) {
        long s = 0;
        for (byte v : row) {
            s += Math.abs((int) v);
        }
        return s;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[64 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
