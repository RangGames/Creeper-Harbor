package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.Geometry;
import wiki.creeper.harbor.boss.logic.Qte;

/**
 * One pattern telegraph (docs 20 10.4 pattern telegraphs). Lays BlockDisplay floor tiles (red
 * glass, translucent) in the area shape, and an edge particle ring shrinks toward the center over
 * the telegraph time. Resolved on the tick the telegraph ends. Markers and particles are sent only
 * to viewers (party members; for world bosses, players within the radius).
 */
final class Telegraph {

    /** Cap on cells covered by one floor tile (limits the entity count, proposed value). */
    private static final int MAX_CELLS = 25;

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.RED, 1.3f);

    final BossDef.PatternDef pattern;
    final Geometry.Area area;
    final long start;
    final long end;
    final double floorY;
    final World world;
    final Set<UUID> insideAtStart = new HashSet<>();

    /** Charge direction of a charge pattern (the captain must input the opposite direction). */
    final Qte.@Nullable Key chargeSide;

    boolean evaded;
    private final List<BlockDisplay> displays = new ArrayList<>();

    Telegraph(
            BossDef.PatternDef pattern,
            Geometry.Area area,
            long start,
            long end,
            double floorY,
            World world,
            Qte.@Nullable Key chargeSide) {
        this.pattern = pattern;
        this.area = area;
        this.start = start;
        this.end = end;
        this.floorY = floorY;
        this.world = world;
        this.chargeSide = chargeSide;
    }

    /**
     * Lays the floor tiles.
     *
     * @param viewers null means visible to everyone (world boss)
     */
    void spawn(Plugin plugin, @Nullable Collection<? extends Player> viewers, float viewRange) {
        double reach =
                area.shape() == BossDef.Shape.LINE
                        ? Math.max(area.length(), area.size())
                        : area.size();
        double step = Math.max(1.0, Math.ceil(reach * 2 / Math.sqrt(MAX_CELLS)));
        for (double[] cell : area.cells(step)) {
            Location at = new Location(world, cell[0], floorY + 0.02, cell[1]);
            BlockDisplay d =
                    world.spawn(
                            at,
                            BlockDisplay.class,
                            e -> {
                                e.setPersistent(false);
                                e.setVisibleByDefault(viewers == null);
                                e.setBlock(Material.RED_STAINED_GLASS.createBlockData());
                                e.setViewRange(viewRange);
                                float s = (float) step;
                                e.setTransformation(
                                        new Transformation(
                                                new Vector3f(-s / 2, 0, -s / 2),
                                                new AxisAngle4f(),
                                                new Vector3f(s, 0.02f, s),
                                                new AxisAngle4f()));
                            });
            if (viewers != null) {
                for (Player p : viewers) {
                    p.showEntity(plugin, d);
                }
            }
            displays.add(d);
        }
    }

    void show(Plugin plugin, Player player) {
        for (BlockDisplay d : displays) {
            if (d.isValid()) {
                player.showEntity(plugin, d);
            }
        }
    }

    /** Shrinking particle ring (called every 2 ticks). */
    void particles(long now, Collection<? extends Player> viewers) {
        double progress = (double) (now - start) / Math.max(1, end - start);
        List<double[]> points = area.outline(28, 1.0 - progress);
        double y = floorY + 0.15;
        for (Player p : viewers) {
            for (double[] pt : points) {
                p.spawnParticle(Particle.DUST, pt[0], y, pt[1], 1, 0, 0, 0, 0, DUST);
            }
        }
    }

    double progress(long now) {
        return Math.clamp((double) (now - start) / Math.max(1, end - start), 0.0, 1.0);
    }

    boolean contains(Location loc) {
        return Math.abs(loc.getY() - floorY) < 4 && area.contains(loc.getX(), loc.getZ());
    }

    void remove() {
        for (BlockDisplay d : displays) {
            d.remove();
        }
        displays.clear();
    }

    int entityCount() {
        return displays.size();
    }
}
