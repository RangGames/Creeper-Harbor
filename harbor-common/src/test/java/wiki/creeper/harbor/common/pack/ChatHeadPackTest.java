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

    @Test
    void fontHasRowGlyphsAndBackSpaces() {
        Map<String, byte[]> files = ChatHeadPack.files();
        JsonObject font =
                JsonParser.parseString(
                                new String(
                                        files.get(ChatHeadPack.FONT_FILE), StandardCharsets.UTF_8))
                        .getAsJsonObject();
        var providers = font.getAsJsonArray("providers");
        JsonObject space = providers.get(0).getAsJsonObject();
        assertEquals(
                -2, space.getAsJsonObject("advances").get("" + ChatHeadPack.BACK_TWO).getAsInt());
        assertEquals(
                -1, space.getAsJsonObject("advances").get("" + ChatHeadPack.BACK_ONE).getAsInt());
        JsonObject bitmap = providers.get(1).getAsJsonObject();
        String chars = bitmap.getAsJsonArray("chars").get(0).getAsString();
        assertEquals(8, chars.length());
        assertEquals(ChatHeadPack.row(0), chars.charAt(0));
        assertEquals(8, bitmap.get("height").getAsInt());
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
}
