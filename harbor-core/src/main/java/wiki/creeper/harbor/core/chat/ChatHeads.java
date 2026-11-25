package wiki.creeper.harbor.core.chat;

import io.lettuce.core.SetArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.pack.ChatHeadPack;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.core.api.ActivePack;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Player heads for text (proposed; the OGminso/ChatHeadFont technique, fonts from ChatHeadPack).
 *
 * <ul>
 *   <li>A player's head follows the skin in their profile right now: each request compares the
 *       profile's skin with the one drawn, so a skin applied after join (SkinsRestorer and the
 *       like) shows up on the next request instead of the account's own skin
 *   <li>Skins are read only from textures.minecraft.net on virtual threads. Faces are cached by
 *       skin URL in memory and in Redis for the whole network (30 days), so a skin is downloaded
 *       once; until a face is in, requests return empty (no head) and the read starts
 *   <li>Other players by name: the skin of a player online here, otherwise the one stored in Redis
 *       {@code harbor:skin:<lowercase name>} when they last joined any server (30 days)
 *   <li>Off with balance.yml {@code chat.heads: false}, and without an auto-built resource pack
 *       (the fonts come from it). Thread-safe (PlaceholderAPI may ask from other threads)
 * </ul>
 */
public final class ChatHeads implements Listener {

    private static final String SKIN_HOST = "textures.minecraft.net";
    private static final String NAME_KEY = "harbor:skin:";
    private static final String FACE_KEY = "harbor:skin_face:";
    private static final long NAME_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final long NAME_REFRESH_MILLIS = 10 * 60 * 1000;
    private static final int CACHE_MAX = 2000;

    private record NameEntry(@Nullable String path, long at) {}

    private final Supplier<ContentSnapshot> content;
    private final Redis redis;
    private final Tasks tasks;
    private final Logger log;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    /** Skin path → face pixels. */
    private final Map<String, int[]> faces = new ConcurrentHashMap<>();

    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    /** Lowercase name → skin path from Redis (PlaceholderAPI may ask from other threads). */
    private final Map<String, NameEntry> byName = new ConcurrentHashMap<>();

    /** "path#size" → head component. */
    private final Map<String, Component> rendered = new ConcurrentHashMap<>();

    public ChatHeads(Supplier<ContentSnapshot> content, Redis redis, Tasks tasks, Logger log) {
        this.content = content;
        this.redis = redis;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::remember);
    }

    // ------------------------------------------------------------------ heads

    /** Default head size (balance.yml chat.head_size, 8). */
    public int defaultSize() {
        return ChatHeadPack.clamp(
                content.get()
                        .single(Balance.TYPE)
                        .integer("chat.head_size", ChatHeadPack.DEFAULT_SIZE));
    }

    /** The player's head and a space at the default size, or empty. */
    public Component head(Player player) {
        return head(player, defaultSize());
    }

    public Component head(Player player, int size) {
        String path = skinPath(player);
        return path == null ? Component.empty() : render(path, size);
    }

    /** Head of a player by name (online here, or as last seen on any server), or empty. */
    public Component head(String name, int size) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return head(online, size);
        }
        String key = name.toLowerCase(Locale.ROOT);
        NameEntry entry = byName.get(key);
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.at() > NAME_REFRESH_MILLIS) {
            lookup(key, entry);
        }
        String path = entry == null ? null : entry.path();
        return path == null ? Component.empty() : render(path, size);
    }

    /**
     * MiniMessage tag {@code <head>}: {@code <head>} the viewer-side player's head, {@code
     * <head:12>} at a size, {@code <head:Name>} another player's, {@code <head:Name:12>} both.
     */
    public TagResolver tag(@Nullable Player self) {
        return TagResolver.resolver(
                "head",
                (args, ctx) -> {
                    String first = args.hasNext() ? args.pop().value() : null;
                    String second = args.hasNext() ? args.pop().value() : null;
                    Integer size = first == null ? null : number(first);
                    if (first == null || (size != null && second == null)) {
                        return Tag.selfClosingInserting(
                                self == null
                                        ? Component.empty()
                                        : head(self, size == null ? defaultSize() : size));
                    }
                    Integer other = second == null ? null : number(second);
                    return Tag.selfClosingInserting(
                            head(first, other == null ? defaultSize() : other));
                });
    }

    /**
     * PlaceholderAPI {@code %harbor_head…%} parameters after "head": "" own head, "_12" size,
     * "_Name" another player, "_Name_12" both (names may contain underscores; a trailing number is
     * the size).
     */
    public Component fromPlaceholder(@Nullable Player self, String rest) {
        if (rest.isEmpty()) {
            return self == null ? Component.empty() : head(self);
        }
        String args = rest.startsWith("_") ? rest.substring(1) : rest;
        int cut = args.lastIndexOf('_');
        Integer whole = number(args);
        if (whole != null) {
            return self == null ? Component.empty() : head(self, whole);
        }
        Integer size = cut < 0 ? null : number(args.substring(cut + 1));
        String name = size == null ? args : args.substring(0, cut);
        return head(name, size == null ? defaultSize() : size);
    }

    private static @Nullable Integer number(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean enabled() {
        return content.get().single(Balance.TYPE).bool("chat.heads", true)
                && ActivePack.published().isPresent();
    }

    private Component render(String path, int size) {
        if (!enabled()) {
            return Component.empty();
        }
        int[] rgb = faces.get(path);
        if (rgb == null) {
            fetch(path);
            return Component.empty();
        }
        int clamped = ChatHeadPack.clamp(size);
        if (rendered.size() >= CACHE_MAX) {
            rendered.clear();
        }
        return rendered.computeIfAbsent(
                path + "#" + clamped, k -> HeadPixels.component(rgb, clamped));
    }

    // ------------------------------------------------------------------ skins

    /** Skin path on textures.minecraft.net in the player's profile right now (null if none). */
    private static @Nullable String skinPath(Player player) {
        URL skin = player.getPlayerProfile().getTextures().getSkin();
        return skin == null || !SKIN_HOST.equals(skin.getHost()) ? null : skin.getPath();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        remember(player);
        // Skin plugins may apply the real skin a little after join
        var _ = tasks.later(100, () -> remember(player));
    }

    /** Starts the skin read and stores the name → skin for other servers. */
    private void remember(Player player) {
        if (!player.isOnline()) {
            return;
        }
        String path = skinPath(player);
        if (path == null) {
            return;
        }
        fetch(path);
        String key = NAME_KEY + player.getName().toLowerCase(Locale.ROOT);
        var _ =
                tasks.async(() -> redis.sync().set(key, path, SetArgs.Builder.ex(NAME_TTL_SECONDS)))
                        .exceptionally(
                                e -> {
                                    log.debug("스킨 주소 저장 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }

    private void lookup(String key, @Nullable NameEntry previous) {
        // Mark first so repeated requests before the answer do not look up again
        byName.put(
                key,
                new NameEntry(
                        previous == null ? null : previous.path(), System.currentTimeMillis()));
        var _ =
                tasks.supplyAsync(() -> redis.sync().get(NAME_KEY + key))
                        .exceptionally(e -> null)
                        .thenAccept(
                                path -> {
                                    if (path != null && path.startsWith("/")) {
                                        byName.put(
                                                key,
                                                new NameEntry(path, System.currentTimeMillis()));
                                        fetch(path);
                                    }
                                });
    }

    private void fetch(String path) {
        if (faces.containsKey(path) || !loading.add(path)) {
            return;
        }
        var _ =
                tasks.supplyAsync(() -> load(path))
                        .whenComplete(
                                (rgb, e) -> {
                                    if (rgb != null) {
                                        if (faces.size() >= CACHE_MAX) {
                                            faces.clear();
                                        }
                                        faces.put(path, rgb);
                                    }
                                    loading.remove(path);
                                });
    }

    /**
     * Face pixels from the shared Redis cache ({@code harbor:skin_face:<skin path>}, 30 days,
     * proposed), else downloaded and stored there, so each skin is downloaded once for the whole
     * network and survives restarts. Redis failures fall through to the download.
     */
    private int @Nullable [] load(String path) {
        String key = FACE_KEY + path;
        try {
            int[] cached = HeadPixels.decode(redis.sync().get(key));
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException e) {
            log.debug("머리 캐시를 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
        }
        int[] rgb = download(path);
        if (rgb != null) {
            try {
                redis.sync().set(key, HeadPixels.encode(rgb), SetArgs.Builder.ex(NAME_TTL_SECONDS));
            } catch (RuntimeException e) {
                log.debug("머리 캐시를 저장하지 못했습니다: {}", String.valueOf(e.getMessage()));
            }
        }
        return rgb;
    }

    /** Face pixels of a skin over HTTPS. Null on any failure. */
    private int @Nullable [] download(String path) {
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
            return image == null ? null : HeadPixels.face(image);
        } catch (IOException | IllegalArgumentException e) {
            log.debug("스킨을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
