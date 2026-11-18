package wiki.creeper.harbor.fishing;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** 위치 도우미. */
public final class Locs {

    private Locs() {}

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 살아 있는 엔티티는 항상 위치가 있다. */
    public static Location of(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
