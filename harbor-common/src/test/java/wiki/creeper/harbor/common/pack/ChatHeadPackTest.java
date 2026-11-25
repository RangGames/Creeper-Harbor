package wiki.creeper.harbor.common.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ChatHeadPackTest {

    private static JsonObject font(Map<String, byte[]> files, int size) {
        return JsonParser.parseString(
                        new String(files.get(ChatHeadPack.fontFile(size)), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    void everySizeStepsOnePixelPerColumn() {
        Map<String, byte[]> files = ChatHeadPack.files();
        for (int size = ChatHeadPack.MIN_SIZE; size <= ChatHeadPack.MAX_SIZE; size++) {
            JsonObject font = font(files, size);
            var providers = font.getAsJsonArray("providers");
            JsonObject advances = providers.get(0).getAsJsonObject().getAsJsonObject("advances");
            double in = advances.get("" + ChatHeadPack.BACK_IN).getAsDouble();
            double end = advances.get("" + ChatHeadPack.BACK_END).getAsDouble();
            JsonObject bitmap = providers.get(1).getAsJsonObject();
            assertEquals(size, bitmap.get("height").getAsInt());
            assertTrue(bitmap.get("ascent").getAsInt() <= size, "ascent ≤ height");
            assertEquals(8, bitmap.getAsJsonArray("chars").get(0).getAsString().length());
            // The game's advance for a 1-pixel glyph of an 8-pixel cell drawn `size` tall
            int glyph = (int) (0.5 + size / 8.0) + 1;
            double pixel = size / 8.0;
            assertEquals(0, glyph + in, 1e-9, "stay in the column at " + size);
            assertEquals(pixel, glyph + end, 1e-3, "next column at " + size);
        }
    }

    @Test
    void textureIsWhiteDiagonal() throws IOException {
        BufferedImage image =
                ImageIO.read(
                        new ByteArrayInputStream(
                                ChatHeadPack.files().get(ChatHeadPack.TEXTURE_FILE)));
        assertEquals(8, image.getWidth());
        assertEquals(8, image.getHeight());
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int a = image.getRGB(x, y) >>> 24;
                assertTrue(x == y ? a == 255 : a == 0, x + "," + y);
            }
        }
    }

    @Test
    void sizesAreClamped() {
        assertEquals("harbor:chat_head_4", ChatHeadPack.font(1));
        assertEquals("harbor:chat_head_16", ChatHeadPack.font(99));
        assertEquals("harbor:chat_head_8", ChatHeadPack.font(8));
    }
}
