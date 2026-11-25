package wiki.creeper.harbor.world.housing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lettuce.core.SetArgs;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.ServerRouter;

/**
 * /부두 나가기 (proposed). Entering housing from outside it remembers where the player was, server
 * included, in Redis {@code harbor:housing:return:<uuid>} (a day), since the housing world may be
 * on another server than the one the player came from. Leaving sends the player back there, or to
 * the harbor plaza (first region in regions.yml) when nothing is remembered or Redis fails. Redis
 * I/O runs on virtual threads.
 */
final class HousingReturn {

    private static final String KEY = "harbor:housing:return:";
    private static final long TTL_SECONDS = 24 * 60 * 60;

    private record Point(String server, ServerRouter.Destination to) {}

    private final WorldKit kit;
    private final ServerRouter router;

    HousingReturn(WorldKit kit, ServerRouter router) {
        this.kit = kit;
        this.router = router;
    }

    /** Remembers where the player is now (call before sending them into housing, main thread). */
    void remember(Player player) {
        Location at = WorldKit.loc(player);
        JsonObject json = new JsonObject();
        json.addProperty("server", kit.api().infra().serverId());
        json.addProperty("world", kit.worldId(at));
        json.addProperty("x", at.getX());
        json.addProperty("y", at.getY());
        json.addProperty("z", at.getZ());
        json.addProperty("yaw", at.getYaw());
        String key = KEY + player.getUniqueId();
        var _ =
                kit.tasks()
                        .async(
                                () ->
                                        kit.api()
                                                .redis()
                                                .sync()
                                                .set(
                                                        key,
                                                        json.toString(),
                                                        SetArgs.Builder.ex(TTL_SECONDS)))
                        .exceptionally(
                                e -> {
                                    kit.log().warn("부두 돌아갈 위치 저장 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }

    /** Sends the player back (main thread). */
    void leave(Player player) {
        String key = KEY + player.getUniqueId();
        var _ =
                kit.tasks()
                        .supplyAsync(() -> Optional.ofNullable(kit.api().redis().sync().get(key)))
                        .exceptionally(
                                e -> {
                                    kit.log().warn("부두 돌아갈 위치 조회 실패: {}", String.valueOf(e));
                                    return Optional.empty();
                                })
                        .thenAcceptAsync(
                                raw -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    Point point = raw.map(this::parse).orElse(null);
                                    if (point == null || !reachable(point)) {
                                        plaza(player);
                                        return;
                                    }
                                    router.send(
                                            player, point.to(), point.server(), "housing_leave");
                                },
                                kit.tasks().main());
    }

    /** Whether the router can take the player there (another server, or a world on this one). */
    private boolean reachable(Point point) {
        if (!point.server().equals(kit.api().infra().serverId())) {
            return true;
        }
        String world = point.to().world();
        return kit.bukkitWorld(world) != null && !world.equals(kit.balance().housingWorld());
    }

    private void plaza(Player player) {
        kit.index().regions().stream()
                .findFirst()
                .ifPresentOrElse(
                        plaza ->
                                router.send(
                                        player,
                                        RegionManager.spawnOf(plaza),
                                        plaza.ext().server(),
                                        "housing_leave"),
                        () -> kit.send(player, "world.route.unavailable"));
    }

    private @Nullable Point parse(String raw) {
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            return new Point(
                    json.get("server").getAsString(),
                    new ServerRouter.Destination(
                            json.get("world").getAsString(),
                            json.get("x").getAsDouble(),
                            json.get("y").getAsDouble(),
                            json.get("z").getAsDouble(),
                            json.get("yaw").getAsFloat()));
        } catch (RuntimeException e) {
            kit.log().warn("부두 돌아갈 위치를 읽지 못했습니다: {}", String.valueOf(e));
            return null;
        }
    }
}
