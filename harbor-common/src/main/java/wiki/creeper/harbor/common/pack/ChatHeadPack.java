package wiki.creeper.harbor.common.pack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Generated fonts for player heads in chat (proposed; the technique of OGminso/ChatHeadFont, CC BY
 * 4.0). One font per head size {@code harbor:chat_head_<size>} ({@link #MIN_SIZE}..{@link
 * #MAX_SIZE} GUI pixels tall), separate from the default font so other packs' default fonts are
 * left alone. Every font has:
 *
 * <ul>
 *   <li>Eight 1-pixel-wide bitmap glyphs {@link #row(int)} U+E000..U+E007, glyph y lighting only
 *       row y (one shared 8×8 texture with a white diagonal), scaled to the size
 *   <li>Spaces {@link #BACK_IN} (back over one glyph: stay in the column) and {@link #BACK_END}
 *       (back over a glyph but one pixel less: move to the next column)
 * </ul>
 *
 * A head is drawn column by column: 8 row glyphs colored with the skin pixel, each followed by
 * BACK_IN except the last, followed by BACK_END.
 */
public final class ChatHeadPack {

    public static final int MIN_SIZE = 4;
    public static final int MAX_SIZE = 16;
    public static final int DEFAULT_SIZE = 8;

    public static final String TEXTURE_FILE = "assets/harbor/textures/font/chat_head.png";

    public static final char BACK_IN = '';
    public static final char BACK_END = '';

    private ChatHeadPack() {}

    /** Glyph that lights pixel row y (0 = top). */
    public static char row(int y) {
        return (char) ('' + y);
    }

    /** Font key for a head size (clamped to the generated range). */
    public static String font(int size) {
        return "harbor:chat_head_" + clamp(size);
    }

    public static int clamp(int size) {
        return Math.clamp(size, MIN_SIZE, MAX_SIZE);
    }

    static String fontFile(int size) {
        return "assets/harbor/font/chat_head_" + size + ".json";
    }

    /**
     * Glyph advance the game gives a 1-pixel-wide glyph of the 8-pixel cell drawn {@code size}
     * pixels tall (bitmap provider: {@code (int) (0.5 + width * scale) + 1}).
     */
    static int glyphAdvance(int size) {
        return (int) (0.5 + size / 8.0) + 1;
    }

    /** The fonts and texture, to add to the pack build's generated files. */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> files = new TreeMap<>();
        for (int size = MIN_SIZE; size <= MAX_SIZE; size++) {
            files.put(fontFile(size), fontJson(size).getBytes(StandardCharsets.UTF_8));
        }
        files.put(TEXTURE_FILE, texture());
        return files;
    }

    static String fontJson(int size) {
        StringBuilder chars = new StringBuilder();
        for (int y = 0; y < 8; y++) {
            chars.append(escape(row(y)));
        }
        double pixel = size / 8.0;
        int advance = glyphAdvance(size);
        // Centred on the text line (normal glyphs: ascent 7, height 8); ascent may not pass height
        int ascent = Math.min(size, (int) Math.floor(3.5 + size / 2.0));
        return "{\"providers\":["
                + "{\"type\":\"space\",\"advances\":{\""
                + escape(BACK_IN)
                + "\":"
                + number(-advance)
                + ",\""
                + escape(BACK_END)
                + "\":"
                + number(pixel - advance)
                + "}},"
                + "{\"type\":\"bitmap\",\"file\":\"harbor:font/chat_head.png\",\"ascent\":"
                + ascent
                + ",\"height\":"
                + size
                + ",\"chars\":[\""
                + chars
                + "\"]}]}";
    }

    /** 8×8, white pixel at (y, y): glyph cell y is column y and lights only row y. */
    static byte[] texture() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            image.setRGB(y, y, 0xFFFFFFFF);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String escape(char c) {
        return String.format(Locale.ROOT, "\\u%04x", (int) c);
    }
}
