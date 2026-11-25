package wiki.creeper.harbor.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.pack.ChatHeadPack;

class HeadPixelsTest {

    @Test
    void faceTakesHatOverFace() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                skin.setRGB(8 + x, 8 + y, 0xFF112233);
            }
        }
        skin.setRGB(40, 8, 0xFFAABBCC); // opaque hat pixel at (0, 0)
        skin.setRGB(41, 8, 0x00FFFFFF); // transparent hat pixel at (1, 0)
        int[] rgb = HeadPixels.face(skin);
        assertNotNull(rgb);
        assertEquals(0xAABBCC, rgb[0]);
        assertEquals(0x112233, rgb[1]);
        assertNull(HeadPixels.face(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)));
    }

    @Test
    void halfTransparentHatBlends() {
        assertEquals(0xFF808080, HeadPixels.blend(0xFF000000, 0x80FFFFFF));
    }

    @Test
    void headAdvancesEightPixelsAcrossEightColumns() {
        int[] rgb = new int[64];
        for (int i = 0; i < 64; i++) {
            rgb[i] = i % 3 == 0 ? 0xFF0000 : 0x00FF00;
        }
        Component head = HeadPixels.component(rgb);
        String text = PlainTextComponentSerializer.plainText().serialize(head);
        // Each row glyph advances 2; BACK_TWO −2, BACK_ONE −1 (see ChatHeadPack)
        int advance = 0;
        int glyphs = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= ChatHeadPack.row(0) && c <= ChatHeadPack.row(7)) {
                advance += 2;
                glyphs++;
            } else if (c == ChatHeadPack.BACK_TWO) {
                advance -= 2;
            } else if (c == ChatHeadPack.BACK_ONE) {
                advance -= 1;
            }
        }
        assertEquals(64, glyphs);
        // 7 columns move 1 each, the last column leaves its glyph advance (2): 9, then the space
        assertEquals(9, advance);
        assertEquals(' ', text.charAt(text.length() - 1));
    }
}
