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
 * 지역 서버 사이 이동 (docs 20 8.1 '어디로 가는지'). 목적지 월드가 이 서버에 있으면 텔레포트하고, 다른 서버에 있으면 도착 위치를 Redis {@code
 * harbor:world:arrive:<uuid>} (60초)에 적은 뒤 Core ProxyBridge.requestMove로 옮긴다. 도착 서버는 접속 직후 그 키를 읽어
 * 옮긴다 (Core가 세션 handoff를 끝낸 뒤라 데이터는 이미 적재돼 있다). Redis 입출력은 가상 스레드에서 한다.
 */
public final class ServerRouter implements Listener {

    /** 목적지 (콘텐츠 월드 이름 기준). */
    public record Destination(String world, double x, double y, double z, float yaw) {}

    private static final String KEY = "harbor:world:arrive:";
    private static final long TTL_SECONDS = 60;

    private final WorldKit kit;

    public ServerRouter(WorldKit kit) {
        this.kit = kit;
    }

    /**
     * @param server 목적지 월드를 돌리는 서버 (null이면 이 서버에 있어야 한다)
     * @return 이동을 시작했으면 true
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
