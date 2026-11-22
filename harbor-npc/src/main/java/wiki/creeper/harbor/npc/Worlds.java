package wiki.creeper.harbor.npc;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.content.Spot;

/**
 * Content world ID (harbor_main etc.) to Bukkit world. Dev servers map via balance.yml world_alias
 * (same rule as Fishing).
 */
public final class Worlds {

    private final HarborApi api;

    public Worlds(HarborApi api) {
        this.api = api;
    }

    public Optional<World> world(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return Optional.of(direct);
        }
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        for (String alias : balance.keys("world_alias")) {
            if (balance.string("world_alias." + alias).orElse("").equals(contentWorld)) {
                World w = Bukkit.getWorld(alias);
                if (w != null) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    /** Bukkit world to content world ID. */
    public String id(World world) {
        return api.content()
                .snapshot()
                .single(Balance.TYPE)
                .string("world_alias." + world.getName())
                .orElse(world.getName());
    }

    /** Paper marks location lookups as nullable, but a living entity always has a location. */
    public static Location loc(org.bukkit.entity.Entity entity) {
        return java.util.Objects.requireNonNull(entity.getLocation());
    }

    public static Location eye(org.bukkit.entity.LivingEntity entity) {
        return java.util.Objects.requireNonNull(entity.getEyeLocation());
    }

    public Optional<Location> location(Spot spot) {
        return world(spot.world())
                .map(w -> new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
    }
}
