package wiki.creeper.harbor.world.spot;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.WaypointService;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.region.RegionManager;

/**
 * Fish school waypoints on the locator bar ("4. Fishing presentation" 1.3, proposed). Every 40
 * ticks each player sees the schools of their current region within balance.yml waypoint.spot_range
 * (× (1 + waypoint_range stat), explorer bonus). Hidden spots appear only with the hidden-spot
 * sense (feature_hidden_spot). Main thread only.
 */
public final class SpotWaypoints {

    private static final String OWNER = "fish_school";

    private final WorldKit kit;
    private final RegionManager regions;
    private final SpotManager spots;

    public SpotWaypoints(WorldKit kit, RegionManager regions, SpotManager spots) {
        this.kit = kit;
        this.regions = regions;
        this.spots = spots;
    }

    public void start() {
        var _ = kit.tasks().timer(40, 40, this::tick);
    }

    private void tick() {
        Balance balance = kit.api().content().snapshot().single(Balance.TYPE);
        double base = balance.number("waypoint.spot_range", 96);
        String style = balance.string("waypoint.styles.spot").orElse(null);
        String color = balance.string("waypoint.colors.spot").orElse(null);
        String hiddenColor = balance.string("waypoint.colors.hidden_spot").orElse(color);
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<WaypointService.Target> targets = new ArrayList<>();
            boolean sense = kit.stat(player, "feature_hidden_spot", 0) > 0;
            double range = base * (1 + kit.stat(player, "waypoint_range", 0));
            Location at = WorldKit.loc(player);
            regions.current(player.getUniqueId())
                    .ifPresent(
                            region -> {
                                for (FishingSpot spot : spots.spots(region)) {
                                    if (spot.hidden() && !sense) {
                                        continue;
                                    }
                                    World world = kit.bukkitWorld(spot.world());
                                    if (world == null
                                            || !world.equals(at.getWorld())
                                            || spot.distanceSquared(at.getX(), at.getZ())
                                                    > range * range) {
                                        continue;
                                    }
                                    targets.add(
                                            new WaypointService.Target(
                                                    spot.id(),
                                                    new Location(
                                                            world, spot.x(), spot.y(), spot.z()),
                                                    spot.hidden() ? hiddenColor : color,
                                                    style,
                                                    range));
                                }
                            });
            kit.api().waypoints().set(player, OWNER, targets);
        }
    }
}
