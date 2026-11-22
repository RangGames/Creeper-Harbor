package wiki.creeper.harbor.core.pack;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.PackStatus;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Resource pack delivery ('16. Plugin design' 5.11). Runs on the lobby server only: on join it
 * sends the base pack + season pack as required packs and reports the result to the proxy as
 * PackStatus (only LOADED enters the queue). On decline or failure the player stays in the lobby
 * and a Dialog explains how to install. A changed pack has a new SHA-1, so it is downloaded again
 * on the next join.
 *
 * <p>When returning from a main server to the lobby within the same connection (/lobby, AFK, NPC),
 * the client still has the packs, so they are not resent: the signature of the applied pack set is
 * stored in Redis {@code harbor:pack_applied:<uuid>}, and if it matches on join, LOADED is reported
 * immediately. The proxy deletes it on disconnect (proposed).
 *
 * <p>Packs sent = harbor-infra.yml resource_packs + the auto-built pack ({@link PackManager}, if
 * any). When the auto-built pack changes, it is resent immediately to players in the lobby.
 */
public final class ResourcePacks implements Listener {

    private final List<ResourcePackInfo> configured;
    private final PackManager packManager;
    private final MessageBus bus;
    private final MessageService messages;
    private final Dialogs dialogs;
    private final Redis redis;
    private final Tasks tasks;
    private final Map<UUID, Set<UUID>> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> requested = new ConcurrentHashMap<>();

    public ResourcePacks(
            InfraConfig infra,
            PackManager packManager,
            MessageBus bus,
            MessageService messages,
            Dialogs dialogs,
            Redis redis,
            Tasks tasks) {
        this.packManager = packManager;
        this.configured =
                infra.resourcePacks().stream()
                        .map(
                                p ->
                                        ResourcePackInfo.resourcePackInfo(
                                                UUID.nameUUIDFromBytes(
                                                        ("harbor-pack:" + p.id())
                                                                .getBytes(StandardCharsets.UTF_8)),
                                                URI.create(p.url()),
                                                p.sha1()))
                        .toList();
        this.bus = bus;
        this.messages = messages;
        this.dialogs = dialogs;
        this.redis = redis;
        this.tasks = tasks;
        packManager.onChange(pack -> Bukkit.getOnlinePlayers().forEach(this::send));
    }

    /** Packs to send now. */
    private List<ResourcePackInfo> packs() {
        List<ResourcePackInfo> all = new ArrayList<>(configured);
        packManager
                .current()
                .ifPresent(
                        p ->
                                all.add(
                                        ResourcePackInfo.resourcePackInfo(
                                                UUID.nameUUIDFromBytes(
                                                        "harbor-pack:auto"
                                                                .getBytes(StandardCharsets.UTF_8)),
                                                URI.create(p.url()),
                                                p.sha1())));
        return all;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<ResourcePackInfo> packs = packs();
        if (packs.isEmpty()) {
            send(player);
            return;
        }
        String signature = signature(packs);
        String key = ProxyMessages.PACK_APPLIED_PREFIX + player.getUniqueId();
        var _ =
                tasks.supplyAsync(() -> redis.sync().get(key))
                        .exceptionally(e -> null)
                        .thenAcceptAsync(
                                applied -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    if (signature.equals(applied)) {
                                        report(player, PackStatus.Status.LOADED);
                                    } else {
                                        send(player);
                                    }
                                },
                                tasks.main());
    }

    /** Pack set signature (ID and SHA-1, sorted). */
    private static String signature(List<ResourcePackInfo> packs) {
        List<String> parts = new ArrayList<>();
        for (ResourcePackInfo p : packs) {
            parts.add(p.id() + "=" + p.hash());
        }
        parts.sort(null);
        return String.join(",", parts);
    }

    private void remember(Player player) {
        String key = ProxyMessages.PACK_APPLIED_PREFIX + player.getUniqueId();
        String signature = signature(packs());
        var _ =
                tasks.async(
                        () ->
                                redis.sync()
                                        .set(
                                                key,
                                                signature,
                                                io.lettuce.core.SetArgs.Builder.ex(
                                                        ProxyMessages.PACK_APPLIED_TTL_SECONDS)));
    }

    private void send(Player player) {
        List<ResourcePackInfo> packs = packs();
        if (packs.isEmpty()) {
            report(player, PackStatus.Status.LOADED);
            return;
        }
        Set<UUID> ids = new HashSet<>();
        packs.forEach(p -> ids.add(p.id()));
        requested.put(player.getUniqueId(), ids);
        loaded.put(player.getUniqueId(), ConcurrentHashMap.newKeySet());
        player.sendResourcePacks(
                ResourcePackRequest.resourcePackRequest()
                        .packs(packs)
                        .required(true)
                        .replace(true)
                        .prompt(messages.get("resourcepack.prompt"))
                        .build());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                Set<UUID> done =
                        loaded.computeIfAbsent(
                                player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
                done.add(event.getID());
                if (done.containsAll(requested.getOrDefault(player.getUniqueId(), Set.of()))) {
                    report(player, PackStatus.Status.LOADED);
                    remember(player);
                }
            }
            case DECLINED -> fail(player, PackStatus.Status.DECLINED);
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    fail(player, PackStatus.Status.FAILED);
            default -> {
                // ACCEPTED, DOWNLOADED: in progress
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
        requested.remove(event.getPlayer().getUniqueId());
    }

    private void fail(Player player, PackStatus.Status status) {
        report(player, status);
        dialogs.notice(
                player,
                messages.get("resourcepack.failed.title"),
                List.of(messages.get("resourcepack.failed.body")));
    }

    private void report(Player player, PackStatus.Status status) {
        bus.publish(
                ProxyMessages.toProxy(PackStatus.class),
                new PackStatus(player.getUniqueId(), status));
    }
}
