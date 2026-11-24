package wiki.creeper.harbor.fishing.present;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Size reveal on catch ("4. Fishing presentation & boss fights" chapter 1 measure step): common ~
 * advanced on the action bar, rare ~ epic as a title, legendary ~ mythic as a measuring animation
 * (numbers counting up while rising in the air, Text Display) before the title. Main thread only.
 */
final class MeasurePresenter {

    private static final int TAPE_TICKS = 36;
    private static final int TAPE_STEP = 2;
    private static final int TAPE_HOLD_TICKS = 40;
    private static final int AFTER_REVEAL_TICKS = 20;
    private static final double TAPE_DISTANCE = 1.6;
    private static final double TAPE_RISE = 0.6;
    private static final double VIEW_RADIUS = 16;

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;

    MeasurePresenter(Plugin plugin, HarborApi api, Tasks tasks) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
    }

    /**
     * Reveals the size and then runs {@code after} (e.g. the result Dialog, so it does not cover
     * the animation).
     *
     * @param capped the quality was capped (the action bar already shows the "missed big fish"
     *     notice, so the action bar reveal is skipped)
     */
    void reveal(
            Player player,
            FishDef fish,
            double sizeCm,
            Quality quality,
            boolean capped,
            FishingPresets.Measure measure,
            boolean tape,
            Runnable after) {
        switch (measure) {
            case ACTIONBAR -> {
                if (!capped) {
                    api.ui()
                            .actionBar(
                                    player,
                                    api.messages()
                                            .get(
                                                    "fishing.present.measure",
                                                    size(sizeCm),
                                                    quality(quality)));
                }
                after.run();
            }
            case TITLE -> {
                title(player, fish, sizeCm, quality);
                after.run();
            }
            case TAPE -> {
                if (tape) {
                    tape(player, fish, sizeCm, quality, after);
                } else {
                    title(player, fish, sizeCm, quality);
                    after.run();
                }
            }
        }
    }

    private void title(Player player, FishDef fish, double sizeCm, Quality quality) {
        api.ui()
                .title(
                        player,
                        api.messages()
                                .get(
                                        "item.name." + fish.grade().key(),
                                        Placeholder.unparsed("name", fish.name())),
                        api.messages()
                                .get("fishing.present.measure_sub", size(sizeCm), quality(quality)),
                        Duration.ofMillis(300),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1));
    }

    /** Measuring animation: the number counts up from 0 while the display rises in front. */
    private void tape(Player player, FishDef fish, double sizeCm, Quality quality, Runnable after) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            dir = new Vector(0, 0, 1);
        }
        Location start = eye.clone().add(dir.normalize().multiply(TAPE_DISTANCE)).add(0, -0.4, 0);
        List<Player> viewers = new ArrayList<>();
        viewers.add(player);
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)
                    && Locs.of(other).distanceSquared(start) <= VIEW_RADIUS * VIEW_RADIUS) {
                viewers.add(other);
            }
        }
        TextDisplay display = Holograms.spawn(plugin, start, tapeText(0), 1.2f, viewers);
        display.setTeleportDuration(TAPE_STEP);
        int[] elapsed = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] =
                tasks.timer(
                        TAPE_STEP,
                        TAPE_STEP,
                        () -> {
                            elapsed[0] += TAPE_STEP;
                            if (!player.isOnline() || !display.isValid()) {
                                task[0].cancel();
                                display.remove();
                                return;
                            }
                            double t = Math.min(1, (double) elapsed[0] / TAPE_TICKS);
                            double eased = 1 - Math.pow(1 - t, 3);
                            display.teleport(start.clone().add(0, TAPE_RISE * eased, 0));
                            if (t < 1) {
                                display.text(tapeText(sizeCm * eased));
                                if (elapsed[0] % (TAPE_STEP * 2) == 0) {
                                    HarborSounds.play(
                                            player,
                                            "harbor:fishing.measure_tick",
                                            CatchPresenter.SOUND);
                                }
                                return;
                            }
                            task[0].cancel();
                            display.text(
                                    api.messages()
                                            .get(
                                                    "fishing.present.tape_done",
                                                    size(sizeCm),
                                                    quality(quality)));
                            Holograms.scaleTo(display, 1.8f, 4);
                            title(player, fish, sizeCm, quality);
                            var _ = tasks.later(TAPE_HOLD_TICKS, display::remove);
                            var _ = tasks.later(AFTER_REVEAL_TICKS, after);
                        });
    }

    private Component tapeText(double sizeCm) {
        return api.messages().get("fishing.present.tape", size(sizeCm));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver size(
            double sizeCm) {
        return Placeholder.unparsed("size", String.format(Locale.ROOT, "%.1f", sizeCm));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver quality(
            Quality quality) {
        return Placeholder.unparsed("quality", quality.display());
    }
}
