package wiki.creeper.harbor.world.housing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.TankBounds;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * Personal aquarium tanks in a plot, 1×1 or larger (housing_decor.yml size · fish, proposed).
 *
 * <ul>
 *   <li>Placing: the placed block is the front-left-bottom corner; the tank grows to the placer's
 *       right (width), away from them (depth) and up (height). Every other block must be free
 *       (replaceable) and inside the same plot, and the plot must have room for all its fish under
 *       the anchor limit. The rest is filled with the tank block
 *   <li>Every tank block carries the decor tag and the tank box ({@link TankBounds#encode()}) in
 *       the chunk PDC, so breaking any of them removes the whole tank
 *   <li>A tank gets one anchor per fish on different blocks spread through the box (anchor
 *       positions are unique in housing_anchor); anchors sharing a box are one tank
 * </ul>
 */
final class Tanks {

    private final WorldKit kit;
    private final HousingManager housing;
    private final DecorPlacement placement;

    Tanks(WorldKit kit, HousingManager housing, DecorPlacement placement) {
        this.kit = kit;
        this.housing = housing;
        this.placement = placement;
    }

    // ------------------------------------------------------------------ bounds tag

    private NamespacedKey boundsKey(Block block) {
        return kit.key("t_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }

    /** The tank box stored on a tank block (absent for 1×1 tanks and plain blocks). */
    Optional<TankBounds> boundsAt(Block block) {
        String raw =
                block.getChunk()
                        .getPersistentDataContainer()
                        .get(boundsKey(block), PersistentDataType.STRING);
        return raw == null
                ? Optional.empty()
                : Optional.ofNullable(TankBounds.decode(kit.worldId(block.getWorld()), raw));
    }

    /** HousingService.tankOf: the stored box when the anchor's chunk is loaded, else 1×1. */
    TankBounds tankOf(DisplayAnchor anchor) {
        World world = kit.bukkitWorld(anchor.world());
        if (world == null || !world.isChunkLoaded(anchor.x() >> 4, anchor.z() >> 4)) {
            return TankBounds.of(anchor);
        }
        return boundsAt(world.getBlockAt(anchor.x(), anchor.y(), anchor.z()))
                .orElse(TankBounds.of(anchor));
    }

    // ------------------------------------------------------------------ placing

    /**
     * Places a tank whose corner block was just placed. Returns false (nothing changed) if it does
     * not fit; the caller cancels the event.
     */
    boolean place(Player player, HousingManager.PlotState plot, DecorDef d, Block corner) {
        int fish = d.fish();
        int limit = kit.balance().housingAnchorLimit();
        if (plot.anchors.size() + fish > limit) {
            kit.actionBar(
                    player,
                    "world.decor.tank_limit",
                    Placeholder.unparsed("limit", String.valueOf(limit)));
            return false;
        }
        BlockFace forward = horizontal(player.getFacing());
        List<Block> cells = cells(corner, forward, d.size());
        Material material = material(d);
        for (Block cell : cells) {
            boolean inPlot = housing.stateAt(cell.getLocation()).map(s -> s == plot).orElse(false);
            boolean free = cell.equals(corner) || (cell.isReplaceable() && !placement.tagged(cell));
            if (!inPlot || !free || material == null) {
                kit.actionBar(
                        player,
                        "world.decor.tank_no_room",
                        Placeholder.unparsed(
                                "size",
                                d.size().get(0) + "×" + d.size().get(1) + "×" + d.size().get(2)));
                return false;
            }
        }
        TankBounds bounds = bounds(kit.worldId(corner.getWorld()), cells);
        for (Block cell : cells) {
            if (!cell.equals(corner)) {
                cell.setType(material, false);
            }
            placement.tag(cell, d);
            if (!bounds.single()) {
                cell.getChunk()
                        .getPersistentDataContainer()
                        .set(boundsKey(cell), PersistentDataType.STRING, bounds.encode());
            }
        }
        BlockFace front = forward.getOppositeFace();
        int first = -1;
        for (Block cell : PlotGrid.spread(cells, fish)) {
            int slot = nextSlot(plot);
            if (first < 0) {
                first = slot;
            }
            DisplayAnchor anchor =
                    new DisplayAnchor(
                            plot.info.plotId(),
                            slot,
                            d.id(),
                            plot.info.world(),
                            cell.getX(),
                            cell.getY(),
                            cell.getZ(),
                            front.name());
            housing.addAnchor(plot, anchor);
            var _ = kit.api().database().asyncVoid(c -> HousingRepository.addAnchor(c, anchor));
            Bukkit.getPluginManager()
                    .callEvent(new DisplayAnchorChangedEvent(plot.info, anchor, true));
        }
        kit.actionBar(
                player,
                fish > 1 ? "world.decor.tank_placed_many" : "world.decor.tank_placed",
                Placeholder.unparsed("slot", String.valueOf(first + 1)),
                Placeholder.unparsed("count", String.valueOf(fish)));
        return true;
    }

    // ------------------------------------------------------------------ removing

    /**
     * Removes the whole tank a broken (or stale) block belongs to: the other tank blocks turn to
     * air, every tag goes and every anchor in the box is removed. The block itself is left to the
     * caller (the break event removes it).
     */
    void remove(Block block, Optional<HousingManager.PlotState> plot, DecorDef d) {
        TankBounds bounds =
                boundsAt(block)
                        .orElse(
                                new TankBounds(
                                        kit.worldId(block.getWorld()),
                                        block.getX(),
                                        block.getY(),
                                        block.getZ(),
                                        block.getX(),
                                        block.getY(),
                                        block.getZ()));
        World world = block.getWorld();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    Block cell = world.getBlockAt(x, y, z);
                    boolean same =
                            placement.placedAt(cell).map(o -> o.id().equals(d.id())).orElse(false);
                    cell.getChunk().getPersistentDataContainer().remove(boundsKey(cell));
                    if (!same) {
                        continue;
                    }
                    placement.untag(cell);
                    if (!cell.equals(block)) {
                        cell.setType(Material.AIR, false);
                    }
                }
            }
        }
        plot.ifPresent(p -> removeAnchors(p, bounds));
    }

    private void removeAnchors(HousingManager.PlotState plot, TankBounds bounds) {
        List<DisplayAnchor> inside =
                plot.anchors.stream()
                        .filter(
                                a ->
                                        a.world().equals(bounds.world())
                                                && bounds.contains(a.x(), a.y(), a.z()))
                        .toList();
        for (DisplayAnchor a : inside) {
            housing.removeAnchor(plot, a.slot());
            long id = a.plotId();
            int slot = a.slot();
            var _ =
                    kit.api()
                            .database()
                            .asyncVoid(c -> HousingRepository.removeAnchor(c, id, slot));
            Bukkit.getPluginManager().callEvent(new DisplayAnchorChangedEvent(plot.info, a, false));
        }
    }

    // ------------------------------------------------------------------ geometry

    private static BlockFace horizontal(BlockFace face) {
        return switch (face) {
            case NORTH, EAST, SOUTH, WEST -> face;
            default -> BlockFace.NORTH;
        };
    }

    private static BlockFace rightOf(BlockFace forward) {
        return switch (forward) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /** Tank blocks, bottom layer first, then front to back, then left to right. */
    static List<Block> cells(Block corner, BlockFace forward, List<Integer> size) {
        BlockFace right = rightOf(forward);
        List<Block> cells = new ArrayList<>();
        for (int y = 0; y < size.get(1); y++) {
            for (int d = 0; d < size.get(2); d++) {
                for (int w = 0; w < size.get(0); w++) {
                    cells.add(
                            corner.getRelative(right, w)
                                    .getRelative(forward, d)
                                    .getRelative(BlockFace.UP, y));
                }
            }
        }
        return cells;
    }

    private static TankBounds bounds(String world, List<Block> cells) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Block b : cells) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        return new TankBounds(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int nextSlot(HousingManager.PlotState plot) {
        int slot = 0;
        while (true) {
            int s = slot;
            if (plot.anchors.stream().noneMatch(a -> a.slot() == s)) {
                return slot;
            }
            slot++;
        }
    }

    private static @Nullable Material material(DecorDef d) {
        String raw = d.material();
        return raw == null ? null : Material.matchMaterial(raw);
    }
}
