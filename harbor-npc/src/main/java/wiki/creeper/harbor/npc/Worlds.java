package wiki.creeper.harbor.npc;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.content.Spot;

/** 콘텐츠 월드 ID(harbor_main 등) ↔ Bukkit 월드. 개발 서버는 balance.yml world_alias로 대응한다 (Fishing과 같은 규칙). */
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

    /** Bukkit 월드 → 콘텐츠 월드 ID. */
    public String id(World world) {
        return api.content()
                .snapshot()
                .single(Balance.TYPE)
                .string("world_alias." + world.getName())
                .orElse(world.getName());
    }

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 살아 있는 엔티티는 항상 위치가 있다. */
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
