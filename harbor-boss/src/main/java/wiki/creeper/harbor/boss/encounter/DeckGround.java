package wiki.creeper.harbor.boss.encounter;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Where players actually stand on the deck, and whether a player has really gone into the water.
 * The arena's deck point comes from content coordinates that may not match the built platform, so
 * the standing spot is searched near it instead of trusting its Y (proposed). Main thread only.
 */
final class DeckGround {

    /** Search range around the configured point (blocks up / down). */
    private static final int UP = 4;

    private static final int DOWN = 24;

    /** Below the deck by this much counts as fallen even without water (void, deep pit). */
    static final double DROP_LIMIT = 12;

    private DeckGround() {}

    /**
     * The nearest spot at the point's x/z with a solid block underneath and room for a player (feet
     * and head passable, not liquid). The point itself if none is found (e.g. open water: no
     * platform built there).
     */
    static Location standable(Location point) {
        World world = point.getWorld();
        if (world == null) {
            return point;
        }
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int y0 = point.getBlockY();
        for (int d = 0; d <= DOWN; d++) {
            if (fits(world, x, y0 - d, z)) {
                return at(point, y0 - d);
            }
            if (d > 0 && d <= UP && fits(world, x, y0 + d, z)) {
                return at(point, y0 + d);
            }
        }
        return point;
    }

    /**
     * Fallen into the water: in water with nothing solid underfoot (swimming or sinking), or far
     * below the deck. Standing on a platform, even in shallow water, is not a fall.
     */
    static boolean fallen(Player player, double deckY) {
        Location at = BossItems.loc(player);
        if (at.getY() < deckY - DROP_LIMIT) {
            return true;
        }
        if (!player.isInWater()) {
            return false;
        }
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return !below.isSolid();
    }

    private static boolean fits(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        return ground.isSolid()
                && feet.isPassable()
                && !feet.isLiquid()
                && head.isPassable()
                && !head.isLiquid();
    }

    private static Location at(Location point, int y) {
        Location out = point.clone();
        out.setY(y);
        return out;
    }
}
