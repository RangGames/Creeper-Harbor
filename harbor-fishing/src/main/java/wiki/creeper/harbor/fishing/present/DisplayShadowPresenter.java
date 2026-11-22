package wiki.creeper.harbor.fishing.present;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Default shadow implementation: a single ItemDisplay (no rig). Created as a hidden real entity,
 * visible only to the caster and not saved (it disappears when the server stops). Movement is
 * smoothed by the client via teleport_duration interpolation. With one display part there is no
 * concern about the 300-per-player cap ("16. Plugin design" chapter 7).
 */
public final class DisplayShadowPresenter implements ShadowPresenter {

    private static final int MAX_TELEPORT_TICKS = 59;
    private static final int JUMP_UP_TICKS = 6;
    private static final int JUMP_HANG_TICKS = 8;
    private static final double JUMP_HEIGHT = 1.8;

    private final Plugin plugin;
    private final Tasks tasks;

    public DisplayShadowPresenter(Plugin plugin, Tasks tasks) {
        this.plugin = plugin;
        this.tasks = tasks;
    }

    @Override
    public Shadow spawn(
            Player viewer,
            Location at,
            FishDef fish,
            FishingPresets.GradePreset preset,
            boolean gradeGlow) {
        ItemStack model = ItemStack.of(Material.COD);
        ItemModels.apply(model, preset.shadowModel()); // Falls back to the vanilla cod look
        float scale = (float) Math.max(0.1, preset.shadowScale());
        String glow = preset.glowColor();
        ItemDisplay display =
                Objects.requireNonNull(at.getWorld(), "그림자 위치에 월드가 없습니다")
                        .spawn(
                                at,
                                ItemDisplay.class,
                                d -> {
                                    d.setPersistent(false);
                                    d.setVisibleByDefault(false);
                                    d.setItemStack(model);
                                    d.setBillboard(Display.Billboard.FIXED);
                                    d.setTransformation(
                                            new Transformation(
                                                    new Vector3f(),
                                                    new Quaternionf().rotateX((float) Math.PI / 2),
                                                    new Vector3f(scale, scale, scale),
                                                    new Quaternionf()));
                                    d.setBrightness(new Display.Brightness(0, 0));
                                    d.setViewRange(0.5f);
                                    if (gradeGlow && glow != null) {
                                        d.setGlowing(true);
                                        d.setGlowColorOverride(
                                                Color.fromRGB(
                                                        Integer.parseInt(glow.substring(1), 16)));
                                    }
                                });
        viewer.showEntity(plugin, display);
        return new DisplayShadow(display, viewer);
    }

    private final class DisplayShadow implements Shadow {

        private final ItemDisplay display;
        private final Player viewer;

        DisplayShadow(ItemDisplay display, Player viewer) {
            this.display = display;
            this.viewer = viewer;
        }

        @Override
        public Location location() {
            return Locs.of(display);
        }

        @Override
        public void moveTo(Location target, int ticks) {
            if (!display.isValid()) {
                return;
            }
            display.setTeleportDuration(Math.clamp(ticks, 0, MAX_TELEPORT_TICKS));
            Location to = target.clone();
            to.setYaw(yawTowards(location(), target));
            to.setPitch(0);
            display.teleport(to);
        }

        @Override
        public void jump(Collection<Player> extraViewers) {
            if (!display.isValid()) {
                return;
            }
            List<Player> extras = List.copyOf(extraViewers);
            for (Player p : extras) {
                if (!p.equals(viewer)) {
                    p.showEntity(plugin, display);
                }
            }
            Location base = location();
            moveTo(base.clone().add(0, JUMP_HEIGHT, 0), JUMP_UP_TICKS);
            var _ =
                    tasks.later(
                            JUMP_UP_TICKS + JUMP_HANG_TICKS,
                            () -> {
                                moveTo(base, JUMP_UP_TICKS);
                                for (Player p : extras) {
                                    if (!p.equals(viewer) && p.isOnline() && display.isValid()) {
                                        p.hideEntity(plugin, display);
                                    }
                                }
                            });
        }

        @Override
        public boolean isValid() {
            return display.isValid();
        }

        @Override
        public void remove() {
            display.remove();
        }
    }

    private static float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1e-6) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
