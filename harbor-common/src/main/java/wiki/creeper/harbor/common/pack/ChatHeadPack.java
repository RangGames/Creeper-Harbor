package wiki.creeper.harbor.common.pack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Generated font for player heads in chat (proposed; the technique of OGminso/ChatHeadFont, CC BY
 * 4.0). Font {@code harbor:chat_head} (separate from the default font, so other packs' default
 * fonts are left alone):
 *
 * <ul>
 *   <li>Eight 1-pixel-wide bitmap glyphs {@link #row(int)} U+E000..U+E007, glyph y lighting only
 *       row y (one 8×8 texture with a white diagonal). Each advances 2 pixels (1 wide + 1 gap)
 *   <li>Spaces {@link #BACK_ONE} (−1) and {@link #BACK_TWO} (−2)
 * </ul>
 *
 * A head is drawn column by column: 8 row glyphs colored with the skin pixel, each followed by
 * BACK_TWO (stay in the column) except the last, followed by BACK_ONE (move one pixel right).
 */
public final class ChatHeadPack {

    public static final String FONT = "harbor:chat_head";
    public static final String FONT_FILE = "assets/harbor/font/chat_head.json";
    public static final String TEXTURE_FILE = "assets/harbor/textures/font/chat_head.png";

    public static final char BACK_ONE = '';
    public static final char BACK_TWO = '';

    private ChatHeadPack() {}

    /** Glyph that lights pixel row y (0 = top). */
    public static char row(int y) {
        return (char) ('' + y);
    }

    /** The font JSON and texture, to add to the pack build's generated files. */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> files = new TreeMap<>();
        files.put(FONT_FILE, fontJson().getBytes(StandardCharsets.UTF_8));
        files.put(TEXTURE_FILE, texture());
        return files;
    }

    static String fontJson() {
        StringBuilder chars = new StringBuilder();
        for (int y = 0; y < 8; y++) {
            chars.append(escape(row(y)));
        }
        return "{\"providers\":["
                + "{\"type\":\"space\",\"advances\":{\""
                + escape(BACK_ONE)
                + "\":-1,\""
                + escape(BACK_TWO)
                + "\":-2}},"
                + "{\"type\":\"bitmap\",\"file\":\"harbor:font/chat_head.png\",\"ascent\":7,"
                + "\"height\":8,\"chars\":[\""
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

    private static String escape(char c) {
        return String.format("\\u%04x", (int) c);
    }
}
