package wiki.creeper.harbor.core.chat;

import java.awt.image.BufferedImage;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.pack.ChatHeadPack;

/**
 * Skin face → chat head component (ChatHeadPack font). No server API besides Adventure (unit
 * tests).
 */
public final class HeadPixels {

    private static final Key FONT = Key.key(ChatHeadPack.FONT);

    private HeadPixels() {}

    /**
     * The 8×8 face of a skin (front of the head at 8,8) with the hat layer (40,8) drawn over it, as
     * RGB in row-major order (index y * 8 + x). Null for an image too small to be a skin.
     */
    public static int @Nullable [] face(BufferedImage skin) {
        if (skin.getWidth() < 64 || skin.getHeight() < 32) {
            return null;
        }
        int[] rgb = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y);
                int hat = skin.getRGB(40 + x, 8 + y);
                rgb[y * 8 + x] = blend(base, hat) & 0xFFFFFF;
            }
        }
        return rgb;
    }

    /** Hat pixel over the face pixel by its alpha (fully transparent hat = face). */
    static int blend(int base, int over) {
        int a = over >>> 24;
        if (a == 0) {
            return base;
        }
        if (a == 255) {
            return over;
        }
        int r = mix(base >> 16 & 0xFF, over >> 16 & 0xFF, a);
        int g = mix(base >> 8 & 0xFF, over >> 8 & 0xFF, a);
        int b = mix(base & 0xFF, over & 0xFF, a);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int mix(int under, int over, int alpha) {
        return (over * alpha + under * (255 - alpha)) / 255;
    }

    /**
     * The head drawn column by column in the chat head font, without text shadow, followed by a
     * space in the normal font. Neighbouring pixels of the same color share one text part.
     */
    public static Component component(int[] rgb) {
        TextComponent.Builder head = Component.text().font(FONT).shadowColor(ShadowColor.none());
        StringBuilder run = new StringBuilder();
        int runColor = -1;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                int color = rgb[y * 8 + x];
                if (color != runColor && !run.isEmpty()) {
                    head.append(Component.text(run.toString(), TextColor.color(runColor)));
                    run.setLength(0);
                }
                runColor = color;
                run.append(ChatHeadPack.row(y));
                if (y < 7) {
                    run.append(ChatHeadPack.BACK_TWO);
                } else if (x < 7) {
                    run.append(ChatHeadPack.BACK_ONE);
                }
            }
        }
        head.append(Component.text(run.toString(), TextColor.color(runColor)));
        return Component.text().append(head.build()).append(Component.space()).build();
    }
}
