package wiki.creeper.harbor.core.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.ActivePack;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Player heads for chat lines ({@code <head>} in chat.format, proposed; the OGminso/ChatHeadFont
 * technique). On join the skin from the player's profile is read on a virtual thread (only from
 * textures.minecraft.net), its face turned into a head component and kept while the player is
 * online. Faces are cached by skin URL, so a server move or a rejoin with the same skin does not
 * download again. Off with balance.yml {@code chat.heads: false}, and without an auto-built
 * resource pack (the font comes from it).
 */
public final class ChatHeads implements Listener {

    private static final String SKIN_HOST = "textures.minecraft.net";
    private static final int CACHE_MAX = 1000;

    private final Supplier<ContentSnapshot> content;
    private final Tasks tasks;
    private final Logger log;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    /** Online players' heads (main thread). */
    private final Map<UUID, Component> heads = new HashMap<>();

    /** Skin URL → face pixels. */
    private final Map<String, int[]> faces = new ConcurrentHashMap<>();

    public ChatHeads(Supplier<ContentSnapshot> content, Tasks tasks, Logger log) {
        this.content = content;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    /** The player's head and a space, or empty (not loaded, off, or no pack). Main thread. */
    public Component head(Player player) {
        if (!content.get().single(Balance.TYPE).bool("chat.heads", true)
                || ActivePack.published().isEmpty()) {
            return Component.empty();
        }
        return heads.getOrDefault(player.getUniqueId(), Component.empty());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heads.remove(event.getPlayer().getUniqueId());
    }

    private void load(Player player) {
        URL skin = player.getPlayerProfile().getTextures().getSkin();
        if (skin == null || !SKIN_HOST.equals(skin.getHost())) {
            return;
        }
        String path = skin.getPath();
        UUID uuid = player.getUniqueId();
        var _ =
                tasks.supplyAsync(() -> face(path))
                        .thenAcceptAsync(
                                rgb -> {
                                    Player online = Bukkit.getPlayer(uuid);
                                    if (rgb != null && online != null) {
                                        heads.put(uuid, HeadPixels.component(rgb));
                                    }
                                },
                                tasks.main());
    }

    /** Face pixels of a skin (cached; downloads over HTTPS). Null on any failure. */
    private int @Nullable [] face(String path) {
        int[] cached = faces.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create("https://" + SKIN_HOST + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            var image = ImageIO.read(new ByteArrayInputStream(response.body()));
            int[] rgb = image == null ? null : HeadPixels.face(image);
            if (rgb != null) {
                if (faces.size() >= CACHE_MAX) {
                    faces.clear();
                }
                faces.put(path, rgb);
            }
            return rgb;
        } catch (IOException | IllegalArgumentException e) {
            log.debug("스킨을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
