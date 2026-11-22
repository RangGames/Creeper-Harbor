package wiki.creeper.harbor.fishing.session;

import java.util.List;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.ShadowDriver;
import wiki.creeper.harbor.fishing.content.FishingPresets;
import wiki.creeper.harbor.fishing.present.ShadowPresenter;

/**
 * Shadow progress for one session (docs 20 3.4). While waiting it starts underwater 6~10 blocks
 * from the bobber and approaches over the wait time, goes under the bobber on the bite, moves left
 * and right with the fish direction during the minigame and leaps on jump patterns. If Wildlife
 * takes over (ShadowDriver), nothing is drawn while waiting and it takes over from that
 * individual's position at the bite. Main thread only.
 */
final class ShadowControl {

    private static final double DEPTH = 1.2;
    private static final int STEP_TICKS = 40;
    private static final double SIDE = 2.0;

    private final ShadowPresenter presenter;
    private final Player viewer;
    private final FishDef fish;
    private final FishingPresets.GradePreset preset;
    private final boolean glow;
    private final Location hook;
    private final int waitTicks;
    private @Nullable ShadowDriver driver;
    private ShadowPresenter.@Nullable Shadow shadow;
    private @Nullable Location start;
    private int elapsed;

    ShadowControl(
            ShadowPresenter presenter,
            Player viewer,
            FishDef fish,
            FishingPresets.GradePreset preset,
            boolean glow,
            Location hook,
            int waitTicks,
            @Nullable ShadowDriver driver,
            RandomGenerator random) {
        this.presenter = presenter;
        this.viewer = viewer;
        this.fish = fish;
        this.preset = preset;
        this.glow = glow;
        this.hook = hook.clone();
        this.waitTicks = Math.max(1, waitTicks);
        this.driver = driver;
        if (driver != null) {
            driver.approach(this.hook, waitTicks);
        } else {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 6 + random.nextDouble() * 4;
            Location from =
                    under(this.hook).add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            this.start = from;
            this.shadow = presenter.spawn(viewer, from, fish, preset, glow);
        }
    }

    private static Location under(Location at) {
        return at.clone().add(0, -DEPTH, 0);
    }

    /**
     * Accepts a takeover individual (FishingService.claimShadow, while waiting). If a default
     * shadow already exists, it is removed and handed over.
     */
    boolean claim(ShadowDriver claimed, int remainingTicks) {
        if (driver != null) {
            return false;
        }
        ShadowPresenter.Shadow own = shadow;
        if (own != null) {
            own.remove();
            shadow = null;
        }
        driver = claimed;
        claimed.approach(hook, remainingTicks);
        return true;
    }

    boolean hasDriver() {
        return driver != null;
    }

    /** One tick while waiting. */
    void tickWaiting() {
        elapsed++;
        if (elapsed % STEP_TICKS != 0) {
            return;
        }
        ShadowDriver d = driver;
        if (d != null) {
            if (!d.isValid()) {
                // If the individual disappears, switch to the default shadow immediately
                d.release(false);
                driver = null;
                start = d.location();
                shadow = presenter.spawn(viewer, under(hook), fish, preset, glow);
            }
            return;
        }
        ShadowPresenter.Shadow own = shadow;
        Location from = start;
        if (own == null || from == null) {
            return;
        }
        double progress = Math.min(1, (double) (elapsed + STEP_TICKS) / waitTicks);
        Location target = under(hook);
        Vector delta = target.toVector().subtract(from.toVector()).multiply(progress);
        own.moveTo(from.clone().add(delta), STEP_TICKS);
    }

    /** Bite: the takeover individual dives and the default shadow takes over at that spot. */
    void bite() {
        ShadowDriver d = driver;
        if (d != null) {
            d.bite();
            Location at = d.location();
            if (shadow == null) {
                shadow = presenter.spawn(viewer, at, fish, preset, glow);
            }
        }
        ShadowPresenter.Shadow own = shadow;
        if (own != null) {
            own.moveTo(under(hook), 4);
            own.bite();
        }
    }

    /** During the minigame: moves left/right toward the fish direction (every 10 ticks). */
    void tickFight(Minigame.Direction direction, int ticks) {
        ShadowPresenter.Shadow own = shadow;
        if (own == null || ticks % 10 != 0) {
            return;
        }
        Location eye = Locs.of(viewer);
        Vector forward = hook.toVector().subtract(eye.toVector()).setY(0);
        if (forward.lengthSquared() < 1e-6) {
            return;
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double side =
                switch (direction) {
                    case LEFT -> -SIDE;
                    case RIGHT -> SIDE;
                    case CENTER -> 0;
                };
        own.moveTo(under(hook).add(right.multiply(side)), 10);
    }

    void jump(List<Player> extraViewers) {
        ShadowPresenter.Shadow own = shadow;
        if (own != null) {
            own.jump(extraViewers);
        }
    }

    /** End: removes the shadow and releases the takeover individual. */
    void close(boolean caught) {
        ShadowPresenter.Shadow own = shadow;
        if (own != null) {
            own.remove();
            shadow = null;
        }
        ShadowDriver d = driver;
        if (d != null) {
            driver = null;
            d.release(caught);
        }
    }
}
