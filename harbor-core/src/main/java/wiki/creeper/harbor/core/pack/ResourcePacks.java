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
 * 리소스팩 적용 ('16. 플러그인 설계' 5.11). 로비 서버에서만 동작한다: 접속하면 기본 팩 + 시즌 팩을 필수 팩으로 보내고, 결과를 프록시에 PackStatus로
 * 알린다 (LOADED만 대기열에 들어간다). 거절·실패면 로비에 머물고 Dialog로 설치 방법을 안내한다. 팩이 바뀌면 SHA-1이 바뀌므로 다음 접속 때 다시 받는다.
 *
 * <p>같은 접속 안에서 본 서버에서 로비로 돌아오면(/로비·잠수·NPC) 클라이언트에 팩이 그대로 있으므로 다시 보내지 않는다: 적용을 마친 팩 묶음의 서명을 Redis
 * {@code harbor:pack_applied:<uuid>}에 남기고, 들어올 때 같으면 바로 LOADED로 알린다. 프록시가 접속을 끊을 때 지운다 (추가 제안).
 *
 * <p>보내는 팩 = harbor-infra.yml resource_packs + 자동 빌드 팩({@link PackManager}, 있으면). 자동 빌드 팩이 바뀌면 로비에
 * 있는 사람에게도 바로 다시 보낸다.
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

    /** 지금 보낼 팩 목록. */
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

    /** 팩 묶음 서명 (ID·SHA-1, 정렬). */
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
                // ACCEPTED, DOWNLOADED: 진행 중
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
