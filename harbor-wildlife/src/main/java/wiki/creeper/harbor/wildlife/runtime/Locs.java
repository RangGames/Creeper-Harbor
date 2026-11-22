package wiki.creeper.harbor.wildlife.runtime;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Location helpers. */
final class Locs {

    private Locs() {}

    /** Paper marks location getters as nullable, but a live entity always has a location. */
    static Location of(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
