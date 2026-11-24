package wiki.creeper.harbor.fishing.present;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Small private Text Displays for fishing presentation (bite mark, measuring numbers, "4. Fishing
 * presentation" 1.3). Hidden real entities that are not saved and shown only to the given viewers.
 * Main thread only.
 */
final class Holograms {

    private static final Color BACKGROUND = Color.fromARGB(96, 0, 0, 0);

    private Holograms() {}

    static TextDisplay spawn(
            Plugin plugin, Location at, Component text, float scale, List<Player> viewers) {
        TextDisplay display =
                Objects.requireNonNull(at.getWorld(), "표시 위치에 월드가 없습니다")
                        .spawn(
                                at,
                                TextDisplay.class,
                                d -> {
                                    d.setPersistent(false);
                                    d.setVisibleByDefault(false);
                                    d.text(text);
                                    d.setBillboard(Display.Billboard.CENTER);
                                    d.setSeeThrough(true);
                                    d.setShadowed(true);
                                    d.setBackgroundColor(BACKGROUND);
                                    d.setBrightness(new Display.Brightness(15, 15));
                                    d.setTransformation(scaled(scale));
                                });
        for (Player viewer : viewers) {
            viewer.showEntity(plugin, display);
        }
        return display;
    }

    /** Smoothly rescales the display over the given ticks (client interpolation). */
    static void scaleTo(TextDisplay display, float scale, int ticks) {
        if (!display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(scaled(scale));
    }

    static Transformation scaled(float scale) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    /** #RRGGBB to a Bukkit color (white if malformed; presets.yml checks the format). */
    static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.substring(1), 16));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return Color.WHITE;
        }
    }
}
