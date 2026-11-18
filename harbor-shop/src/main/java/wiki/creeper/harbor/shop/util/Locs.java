package wiki.creeper.harbor.shop.util;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** 엔티티 위치 (Paper API가 @Nullable로 표시해 둔 getLocation을 널 아님으로 받는다). */
public final class Locs {

    private Locs() {}

    public static Location of(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
