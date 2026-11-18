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
 * 패턴 예고 하나 (docs 20 10.4 패턴 예고). 범위 모양대로 BlockDisplay 바닥 판(빨간 유리, 반투명)을 깔고, 가장자리 파티클 링이 예고 시간 동안 중심
 * 쪽으로 줄어든다. 예고가 끝나는 틱에 판정한다. 표식과 파티클은 시청자(파티원, 월드 보스는 반경 안 플레이어)에게만 보낸다.
 */
final class Telegraph {

    /** 바닥 판 하나가 덮는 칸 수 상한 (엔티티 수를 묶는다, 제안값). */
    private static final int MAX_CELLS = 25;

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.RED, 1.3f);

    final BossDef.PatternDef pattern;
    final Geometry.Area area;
    final long start;
    final long end;
    final double floorY;
    final World world;
    final Set<UUID> insideAtStart = new HashSet<>();

    /** 돌진 패턴의 돌진 방향 (선장은 반대 방향을 입력해야 한다). */
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
     * 바닥 판을 깐다.
     *
     * @param viewers null이면 모두에게 보인다 (월드 보스)
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

    /** 줄어드는 파티클 링 (2틱마다 부른다). */
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
