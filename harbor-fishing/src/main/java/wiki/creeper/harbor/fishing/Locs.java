package wiki.creeper.harbor.fishing;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Location helpers. */
public final class Locs {

    private Locs() {}

    /** Paper marks location lookups as nullable, but a living entity always has a location. */
    public static Location of(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
