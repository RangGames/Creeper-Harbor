package wiki.creeper.harbor.world.region;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lettuce.core.SetArgs;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;

/**
 * Travel between region servers (docs 20 8.1 'where to go'). If the destination world is on this
 * server it teleports; if it is on another server it writes the arrival position to Redis {@code
 * harbor:world:arrive:<uuid>} (60 seconds) and moves the player with Core ProxyBridge.requestMove.
 * The arrival server reads that key right after join and moves the player (Core has finished the
 * session handoff by then, so the data is already loaded). Redis I/O runs on virtual threads.
 */
public final class ServerRouter implements Listener {

    /** Destination (by content world name). */
    public record Destination(String world, double x, double y, double z, float yaw) {}

    private static final String KEY = "harbor:world:arrive:";
    private static final long TTL_SECONDS = 60;

    private final WorldKit kit;

    public ServerRouter(WorldKit kit) {
        this.kit = kit;
    }

    /**
     * @param server server running the destination world (if null, it must be on this server)
     * @return true if the move started
     */
    public boolean send(Player player, Destination to, @Nullable String server, String reason) {
        World world = kit.bukkitWorld(to.world());
        if (world != null) {
            var _ =
                    player.teleportAsync(
                            new Location(world, to.x(), to.y(), to.z(), to.yaw(), 0),
                            PlayerTeleportEvent.TeleportCause.PLUGIN);
            return true;
        }
        if (server == null || server.isBlank() || server.equals(kit.api().infra().serverId())) {
            kit.send(player, "world.route.unavailable");
            return false;
        }
        UUID uuid = player.getUniqueId();
        JsonObject json = new JsonObject();
        json.addProperty("world", to.world());
        json.addProperty("x", to.x());
        json.addProperty("y", to.y());
        json.addProperty("z", to.z());
        json.addProperty("yaw", to.yaw());
        kit.send(player, "world.route.moving");
        var _ =
                kit.tasks()
                        .async(
                                () ->
                                        kit.api()
                                                .redis()
                                                .sync()
                                                .set(
                                                        KEY + uuid,
                                                        json.toString(),
                                                        SetArgs.Builder.ex(TTL_SECONDS)))
                        .thenComposeAsync(
                                v -> kit.api().proxy().requestMove(uuid, server, "world:" + reason),
                                kit.tasks().main())
                        .whenCompleteAsync(
                                (result, error) -> {
                                    if (error != null || result == null || !result.ok()) {
                                        kit.send(player, "world.route.failed");
                                        var _ =
                                                kit.tasks()
                                                        .async(
                                                                () ->
                                                                        kit.api()
                                                                                .redis()
                                                                                .sync()
                                                                                .del(KEY + uuid));
                                    }
                                },
                                kit.tasks().main());
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        var _ =
                kit.tasks()
                        .supplyAsync(
                                () -> {
                                    String raw = kit.api().redis().sync().get(KEY + uuid);
                                    if (raw != null) {
                                        kit.api().redis().sync().del(KEY + uuid);
                                    }
                                    return raw;
                                })
                        .thenAcceptAsync(
                                raw -> {
                                    if (raw == null || !player.isOnline()) {
                                        return;
                                    }
                                    try {
                                        JsonObject json =
                                                JsonParser.parseString(raw).getAsJsonObject();
                                        World world =
                                                kit.bukkitWorld(json.get("world").getAsString());
                                        if (world == null) {
                                            return;
                                        }
                                        var _ =
                                                player.teleportAsync(
                                                        new Location(
                                                                world,
                                                                json.get("x").getAsDouble(),
                                                                json.get("y").getAsDouble(),
                                                                json.get("z").getAsDouble(),
                                                                json.get("yaw").getAsFloat(),
                                                                0),
                                                        PlayerTeleportEvent.TeleportCause.PLUGIN);
                                    } catch (RuntimeException e) {
                                        kit.log().warn("도착 위치를 읽지 못했습니다: {}", String.valueOf(e));
                                    }
                                },
                                kit.tasks().main())
                        .exceptionally(
                                e -> {
                                    kit.log().warn("도착 위치 조회 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }
}
