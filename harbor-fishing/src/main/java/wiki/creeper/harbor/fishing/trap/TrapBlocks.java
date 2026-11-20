package wiki.creeper.harbor.fishing.trap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.core.api.HarborApi;

/** 통발의 월드 표현: 배럴 블록(PDC에 통발 ID)과 위의 ItemDisplay 외형, 부표 자리 찾기. 메인 스레드 전용. */
final class TrapBlocks {

    private final HarborApi api;
    private final NamespacedKey trapKey;

    TrapBlocks(Plugin plugin, HarborApi api) {
        this.api = api;
        this.trapKey = new NamespacedKey(plugin, "trap_id");
    }

    @Nullable Long trapIdAt(Block block) {
        if (block.getType() != Material.BARREL) {
            return null;
        }
        BlockState state = block.getState(false);
        if (state instanceof TileState tile) {
            return tile.getPersistentDataContainer().get(trapKey, PersistentDataType.LONG);
        }
        return null;
    }

    static RegionDef.@Nullable Point nearestBuoy(RegionDef region, Location at, double radius) {
        RegionDef.Point best = null;
        double bestDist = radius * radius;
        for (RegionDef.Point p : region.trapZone()) {
            double dx = p.x() - at.getX();
            double dz = p.z() - at.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /** 배럴 블록 + 위에 통발 외형 ItemDisplay. */
    void build(Block spot, long id, ItemDef def) {
        spot.setType(Material.BARREL);
        BlockState state = spot.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(trapKey, PersistentDataType.LONG, id);
            tile.update();
        }
        ItemStack look = api.items().create(def, 1);
        var _ =
                spot.getWorld()
                        .spawn(
                                spot.getLocation().add(0.5, 1.35, 0.5),
                                ItemDisplay.class,
                                d -> {
                                    d.setItemStack(look);
                                    d.setBillboard(Display.Billboard.VERTICAL);
                                    d.setPersistent(true);
                                    d.getPersistentDataContainer()
                                            .set(trapKey, PersistentDataType.LONG, id);
                                });
    }

    /** 블록과 외형을 지운다 (청크가 로드되어 있을 때만, 아니면 다음에 누가 열 때 정리). */
    void demolish(String worldName, int x, int y, int z, long id) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        Long at = trapIdAt(block);
        if (at != null && at == id) {
            block.setType(Material.AIR);
        }
        Location center = block.getLocation().add(0.5, 1, 0.5);
        for (Entity e : world.getNearbyEntities(center, 1.5, 2, 1.5)) {
            Long tag = e.getPersistentDataContainer().get(trapKey, PersistentDataType.LONG);
            if (e instanceof ItemDisplay && tag != null && tag == id) {
                e.remove();
            }
        }
    }
}
