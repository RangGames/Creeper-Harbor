package wiki.creeper.harbor.fishing.present;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Bobber-side presentation ("4. Fishing presentation & boss fights" chapter 1 cast · wait · bite,
 * 1.3 implementation): the cast line trail (dust particles following the bobber in flight), the
 * landing splash, the waiting ambience (legendary darkened water and low rumble) and the bite "!"
 * Text Display above the bobber. Main thread only.
 */
final class BobberEffects {

    private static final float TRAIL_SIZE = 0.7f;
    private static final double MARK_HEIGHT = 0.9;
    private static final float MARK_SCALE = 1.8f;
    private static final int MARK_POP_TICKS = 3;
    private static final int MARK_EXTRA_TICKS = 10;
    private static final int LAND_SPLASH = 12;

    private final Plugin plugin;
    private final Tasks tasks;
    private final Map<UUID, TextDisplay> marks = new HashMap<>();

    BobberEffects(Plugin plugin, Tasks tasks) {
        this.plugin = plugin;
        this.tasks = tasks;
    }

    /** One tick of the bobber in flight: a dust point at the bobber (the arc stays drawn). */
    void trail(Location bobber, @Nullable String color) {
        World world = bobber.getWorld();
        if (world == null || color == null) {
            return;
        }
        world.spawnParticle(
                Particle.DUST,
                bobber,
                2,
                0.02,
                0.02,
                0.02,
                0,
                new Particle.DustOptions(Holograms.color(color), TRAIL_SIZE));
    }

    /** Landing splash at the bobber. */
    void land(Location at) {
        World world = at.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.SPLASH, at, LAND_SPLASH, 0.25, 0.05, 0.25, 0.1);
        }
    }

    /** Waiting ambience heard and seen only by the caster. */
    void waiting(Player player, Location hook, FishingPresets.Stage stage) {
        HarborSounds.playAt(
                player,
                hook,
                stage.sound(),
                CatchPresenter.SOUND.withFallback(stage.soundFallback()));
        String name = stage.particle();
        Particle particle = name == null ? null : CatchPresenter.resolve(name);
        if (particle != null && stage.particleCount() > 0) {
            try {
                player.spawnParticle(particle, hook, stage.particleCount(), 1.2, 0.1, 1.2, 0.01);
            } catch (IllegalArgumentException e) {
                // Skip particles that need data
            }
        }
    }

    /**
     * Bite mark: the grade's mark text pops up above the bobber for the bite window, visible only
     * to the caster.
     */
    void mark(Player player, Location hook, FishingPresets.GradePreset preset, int windowTicks) {
        clearMark(player);
        if (preset.mark().isEmpty()) {
            return;
        }
        Component text =
                Component.text(
                        preset.mark(),
                        TextColor.color(Holograms.color(preset.markColor()).asRGB()),
                        TextDecoration.BOLD);
        TextDisplay display =
                Holograms.spawn(
                        plugin, hook.clone().add(0, MARK_HEIGHT, 0), text, 0.2f, List.of(player));
        marks.put(player.getUniqueId(), display);
        var _ = tasks.later(1, () -> Holograms.scaleTo(display, MARK_SCALE, MARK_POP_TICKS));
        var _ =
                tasks.later(
                        Math.max(20, windowTicks) + MARK_EXTRA_TICKS,
                        () -> {
                            // Only if it is still this bite's mark (a new bite replaces it)
                            marks.remove(player.getUniqueId(), display);
                            display.remove();
                        });
    }

    void clearMark(Player player) {
        TextDisplay display = marks.remove(player.getUniqueId());
        if (display != null) {
            display.remove();
        }
    }

    void clearAll() {
        for (TextDisplay display : marks.values()) {
            display.remove();
        }
        marks.clear();
    }
}
