package wiki.creeper.harbor.wildlife.api;

import org.bukkit.Location;

/**
 * Hit result of {@link WildlifeService#raycast}. The calling module decides what to grant (harpoon
 * throw, boss cinematic).
 *
 * @param point the point where the ray first touched the body sphere
 * @param distance distance from the origin to the hit point
 */
public record WildHit(WildFish fish, Location point, double distance) {

    public WildHit {
        point = point.clone();
    }

    @Override
    public Location point() {
        return point.clone();
    }
}
