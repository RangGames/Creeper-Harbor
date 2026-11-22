package wiki.creeper.harbor.shop.util;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Entity location (takes getLocation, which the Paper API marks @Nullable, as non-null). */
public final class Locs {

    private Locs() {}

    public static Location of(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
