package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.present.CatchPresenter;

/**
 * Minigame input interpretation and screen/sound (tension gauge, boss bar color, reel sound,
 * warning border, legendary sky presentation, movement lock). Main thread only.
 */
final class MinigameDisplay {

    private static final int GAUGE_CELLS = 20;
    private static final float LOOK_THRESHOLD = 12f;
    private static final double JUMP_VIEW_RADIUS = 24;

    private final HarborApi api;
    private final Tasks tasks;
    private final CatchPresenter presenter;
    private final CastContext ctx;
    private final NamespacedKey moveLock;

    MinigameDisplay(
            Plugin plugin, HarborApi api, Tasks tasks, CatchPresenter presenter, CastContext ctx) {
        this.api = api;
        this.tasks = tasks;
        this.presenter = presenter;
        this.ctx = ctx;
        this.moveLock = new NamespacedKey(plugin, "minigame_lock");
    }

    /**
     * Direction input: movement keys or camera rotation (setting). A/D input arrives as is even on
     * a boat, so keys are used (previously boats switched to camera rotation, but when the boat
     * turned the camera turned with it, so the difference from the starting direction persisted and
     * the input got stuck to one side).
     */
    Minigame.Input input(
            Player player,
            boolean sneak,
            boolean left,
            boolean right,
            boolean look,
            float baseYaw) {
        if (look) {
            float delta = wrap(loc(player).getYaw() - baseYaw);
            return new Minigame.Input(sneak, delta < -LOOK_THRESHOLD, delta > LOOK_THRESHOLD);
        }
        return new Minigame.Input(sneak, left, right);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /**
     * Reel looping sound: on while Shift is held, cut when released ("15. Sound" chapter 2
     * fishing.reel_loop). Follows the player and is audible nearby.
     */
    HarborSounds.Loop reelSound(Player player, boolean reeling, HarborSounds.Loop current) {
        if (reeling && !current.active()) {
            return HarborSounds.loopFrom(
                    tasks, player, presenter.stepSound("reel_loop"), CatchPresenter.SOUND);
        }
        if (!reeling && current.active()) {
            current.stop();
            return HarborSounds.Loop.NONE;
        }
        return current;
    }

    /**
     * Where the bobber landed: lava landing for lava, ice hole landing for an ice hole (ice beside
     * or above), otherwise a normal landing ("15. Sound" 4.1).
     */
    static String landStep(Location at) {
        Block block = at.getBlock();
        if (block.getType() == Material.LAVA) {
            return "land_lava";
        }
        for (BlockFace face :
                List.of(
                        BlockFace.UP,
                        BlockFace.NORTH,
                        BlockFace.SOUTH,
                        BlockFace.EAST,
                        BlockFace.WEST)) {
            if (Tag.ICE.isTagged(block.getRelative(face).getType())) {
                return "land_ice";
            }
        }
        return "land";
    }

    /**
     * At tension 0.85 or above, requests a warning from Core WorldBorderService to redden the
     * screen edges.
     */
    void warnBorder(
            Player player,
            double tension,
            boolean active,
            java.util.function.Consumer<Boolean> set) {
        boolean warn = tension >= ctx.balance().tensionWarn();
        if (warn && !active) {
            presenter.step(loc(player), "tension_warn");
            api.worldBorders()
                    .request(
                            player,
                            WorldBorderService.Priority.FISHING_TENSION,
                            WorldBorderService.Border.warning(loc(player)));
            set.accept(true);
        } else if (!warn && active) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
            set.accept(false);
        }
    }

    /** Legendary phase 3 sky presentation (personal weather and time). */
    void phaseSky(Player player, int phase, int intensity, FishDef fish) {
        if (!fish.grade().isAtLeast(Grade.LEGENDARY)
                || !ctx.presets().shows(intensity, "darkness")) {
            return;
        }
        if (phase == 2) {
            player.setPlayerWeather(WeatherType.DOWNFALL);
        } else if (phase >= 3) {
            player.setPlayerTime(18000, false);
        }
        api.messages()
                .send(
                        player,
                        "fishing.minigame.phase",
                        Placeholder.unparsed("phase", String.valueOf(phase)));
    }

    List<Player> nearby(Player player) {
        Location at = loc(player);
        List<Player> result = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)
                    && loc(other).distanceSquared(at) <= JUMP_VIEW_RADIUS * JUMP_VIEW_RADIUS) {
                result.add(other);
            }
        }
        return result;
    }

    /**
     * Tension gauge (20 cells): safe zone green, danger (0.85+) red, needle ▼. ◀◀ / ▶▶ on the side
     * the fish is fleeing to (green if following, red otherwise). While holding center, a red arrow
     * on the pressed side if A/D is held. Switch to Ui glyphs once custom font glyphs are ready.
     */
    static Component gauge(Minigame game, Minigame.Input input) {
        int needle = (int) Math.min(GAUGE_CELLS - 1, Math.floor(game.tension() * GAUGE_CELLS));
        Component bar = Component.empty();
        for (int i = 0; i < GAUGE_CELLS; i++) {
            double pos = (i + 0.5) / GAUGE_CELLS;
            NamedTextColor color =
                    pos >= 0.85
                            ? NamedTextColor.RED
                            : pos >= game.safeLow() && pos <= game.safeHigh()
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.DARK_GRAY;
            bar =
                    bar.append(
                            Component.text(
                                    i == needle ? "▼" : "|",
                                    i == needle ? NamedTextColor.WHITE : color));
        }
        NamedTextColor cue =
                game.followsDirection(input) ? NamedTextColor.GREEN : NamedTextColor.RED;
        boolean left =
                switch (game.direction()) {
                    case LEFT -> true;
                    case RIGHT -> false;
                    case CENTER -> input.left();
                };
        boolean right =
                switch (game.direction()) {
                    case LEFT -> false;
                    case RIGHT -> true;
                    case CENTER -> input.right();
                };
        return Component.text(left ? "◀◀ " : "    ", cue)
                .append(bar)
                .append(Component.text(right ? " ▶▶" : "    ", cue));
    }

    static BossBar.Color barColor(Grade grade) {
        return switch (grade) {
            case COMMON -> BossBar.Color.WHITE;
            case UNCOMMON -> BossBar.Color.GREEN;
            case RARE -> BossBar.Color.BLUE;
            case EPIC -> BossBar.Color.PURPLE;
            case LEGENDARY -> BossBar.Color.YELLOW;
            case MYTHIC -> BossBar.Color.RED;
        };
    }

    void lockMovement(Player player, boolean lock) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(moveLock);
        if (lock) {
            speed.addTransientModifier(
                    new AttributeModifier(
                            moveLock, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }
}
