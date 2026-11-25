package wiki.creeper.harbor.world.api;

import org.jspecify.annotations.Nullable;

/**
 * Block box of a personal aquarium tank (both ends inclusive). A 1×1 tank is its anchor block; a
 * larger tank (housing_decor.yml size) spans several blocks and has one {@link DisplayAnchor} per
 * fish, each on a different block inside the box, so anchors with the same bounds are one tank.
 */
public record TankBounds(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** The 1×1 tank of an anchor. */
    public static TankBounds of(DisplayAnchor anchor) {
        return new TankBounds(
                anchor.world(),
                anchor.x(),
                anchor.y(),
                anchor.z(),
                anchor.x(),
                anchor.y(),
                anchor.z());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public boolean single() {
        return minX == maxX && minY == maxY && minZ == maxZ;
    }

    /** "minX,minY,minZ,maxX,maxY,maxZ" (stored in the chunk PDC of each tank block). */
    public String encode() {
        return minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
    }

    /** Parses {@link #encode()}; null if malformed. */
    public static @Nullable TankBounds decode(String world, String raw) {
        String[] p = raw.split(",", -1);
        if (p.length != 6) {
            return null;
        }
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) {
                v[i] = Integer.parseInt(p[i].trim());
            }
            return new TankBounds(world, v[0], v[1], v[2], v[3], v[4], v[5]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
