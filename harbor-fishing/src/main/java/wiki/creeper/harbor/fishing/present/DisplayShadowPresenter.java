package wiki.creeper.harbor.fishing.present;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.key.Key;
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
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * 기본 그림자 구현: ItemDisplay 하나 (리그 없음). 숨긴 실제 엔티티로 만들고 캐스터에게만 보이며, 저장하지 않는다 (서버가 꺼지면 사라짐). 이동은
 * teleport_duration 보간으로 클라이언트가 부드럽게 그린다. 표시 부품 1개라 플레이어당 300개 상한('16. 플러그인 설계' 7장) 걱정이 없다.
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
        try {
            model.setData(DataComponentTypes.ITEM_MODEL, Key.key(preset.shadowModel()));
        } catch (IllegalArgumentException e) {
            // 모델 키가 틀리면 바닐라 대구 모양
        }
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
