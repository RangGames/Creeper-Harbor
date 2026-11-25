package wiki.creeper.harbor.collection.display;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Map;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.PlotView;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotRef;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotView;
import wiki.creeper.harbor.collection.service.Locs;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotPermission;

/**
 * Tank clicks: right-click opens the edit menu for players who may edit (sneak for info) and info
 * for visitors; left-click breaks the tank for builders. Handles both the click box and the tank
 * block, since the client may send either. Main thread only.
 */
final class DisplayCaseClicks implements Listener {

    private final HousingService housing;
    private final DisplayCaseMenus menus;
    private final DisplayCaseHover hover;
    private final Map<Long, PlotView> plots;
    private final Map<UUID, SlotRef> boxes;

    DisplayCaseClicks(
            HousingService housing,
            DisplayCaseMenus menus,
            DisplayCaseHover hover,
            Map<Long, PlotView> plots,
            Map<UUID, SlotRef> boxes) {
        this.housing = housing;
        this.menus = menus;
        this.hover = hover;
        this.plots = plots;
        this.boxes = boxes;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        SlotRef ref = boxes.get(clicked.getUniqueId());
        if (ref == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true);
        click(event.getPlayer(), ref);
    }

    /**
     * Right-click on the tank block itself. The click box is exactly the block, and the client
     * picks the block on a tie, so depending on rounding a click reaches the block instead of the
     * box.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickBlock(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || block == null) {
            return;
        }
        SlotRef ref = slotAt(block);
        if (ref == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        click(event.getPlayer(), ref);
    }

    /**
     * Left-click that the client sent to the click box instead of the tank block (same tie): break
     * the tank as the player, so World's break handling (permission, decor return) still runs.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        SlotRef ref = boxes.get(event.getAttacked().getUniqueId());
        if (ref == null) {
            return;
        }
        event.setCancelled(true);
        PlotView view = plots.get(ref.plotId());
        Player player = event.getPlayer();
        if (view != null && housing.can(player, view.plot, PlotPermission.BUILD)) {
            // The box stands on the bottom block of its column of the tank
            var _ = player.breakBlock(Locs.of(event.getAttacked()).getBlock());
        }
    }

    private @Nullable SlotRef slotAt(Block block) {
        String world = block.getWorld().getName();
        for (PlotView view : plots.values()) {
            if (!view.plot.world().equals(world)) {
                continue;
            }
            for (Map.Entry<Integer, SlotView> e : view.views.entrySet()) {
                SlotView sv = e.getValue();
                if (sv.tank.contains(block.getX(), block.getY(), block.getZ())) {
                    return new SlotRef(view.plot.plotId(), sv.head(e.getKey()));
                }
            }
        }
        return null;
    }

    private void click(Player player, SlotRef ref) {
        PlotView view = plots.get(ref.plotId());
        if (view == null) {
            return;
        }
        boolean editor =
                housing.can(player, view.plot, PlotPermission.EDIT_DISPLAY) && !player.isSneaking();
        SlotView sv = view.views.get(ref.slot());
        if (sv != null && sv.mates.size() > 1) {
            if (editor) {
                menus.openTank(player, view, sv.mates);
            } else if (!hover.showing(player, ref)) {
                menus.showTankInfo(player, view, sv);
            }
            return;
        }
        if (editor) {
            menus.openEditor(player, view, ref.slot());
        } else if (!hover.showing(player, ref)) {
            menus.showInfo(player, view, ref.slot());
        }
    }
}
