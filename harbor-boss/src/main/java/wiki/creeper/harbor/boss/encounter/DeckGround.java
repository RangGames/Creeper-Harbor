package wiki.creeper.harbor.boss.encounter;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Where players actually stand on the deck of one encounter, and whether a player has really gone
 * into the water. The arena's deck point comes from content coordinates that may not match the
 * built platform, so the standing spot is searched near it instead of trusting its Y (proposed).
 * Main thread only.
 */
final class DeckGround {

    /** Search range around the configured point (blocks up / down). */
    private static final int UP = 4;

    private static final int DOWN = 24;

    /** Below the deck by this much counts as fallen even without water (void, deep pit). */
    static final double DROP_LIMIT = 12;

    private final Encounter e;
    private @Nullable Location spawn;

    /** Whether a platform was found at the deck point (false: open water, nothing built yet). */
    private boolean platform;

    DeckGround(Encounter e) {
        this.e = e;
    }

    /** Deck spawn on the actual platform near the configured point (searched once per fight). */
    Location spawn() {
        Location s = spawn;
        if (s == null) {
            Location point = e.deck(e.def.arena().deck().spawn());
            Location found = standable(point);
            platform = found != null;
            if (found == null) {
                // Without a platform every player stands in water: the water rule would throw
                // them into the fall loop (blindness, teleport back into the same water) forever
                e.mgr.log()
                        .warn(
                                "보스 갑판 {} 슬롯 {}에 설 수 있는 바닥이 없습니다 ({}, {}, {}). 경기장에"
                                        + " 갑판을 지어 주세요. 그 전까지 물에 빠짐은 높이로만 판정합니다",
                                e.def.id(),
                                e.slot,
                                point.getBlockX(),
                                point.getBlockY(),
                                point.getBlockZ());
            }
            s = found == null ? point : found;
            spawn = s;
        }
        return s.clone();
    }

    /**
     * Fallen into the water: in water with nothing solid underfoot (swimming or sinking), or far
     * below the deck. Standing on a platform, even in shallow water, is not a fall. With no
     * platform at the deck point only the height rule applies.
     */
    boolean fallen(Player player) {
        double deckY = spawn().getY();
        Location at = BossItems.loc(player);
        if (at.getY() < deckY - DROP_LIMIT) {
            return true;
        }
        if (!platform || !player.isInWater()) {
            return false;
        }
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return !below.isSolid();
    }

    /**
     * The nearest spot at the point's x/z with a solid block underneath and room for a player (feet
     * and head passable, not liquid). Null if none is found (e.g. open water: no platform built).
     */
    static @Nullable Location standable(Location point) {
        World world = point.getWorld();
        if (world == null) {
            return null;
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
        return null;
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
