package wiki.creeper.harbor.core.pack;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
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
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * 리소스팩 적용 ('16. 플러그인 설계' 5.11). 로비 서버에서만 동작한다: 접속하면 기본 팩 + 시즌 팩을 필수 팩으로 보내고, 결과를 프록시에 PackStatus로
 * 알린다 (LOADED만 대기열에 들어간다). 거절·실패면 로비에 머물고 Dialog로 설치 방법을 안내한다. 팩이 바뀌면 SHA-1이 바뀌므로 다음 접속 때 다시 받는다.
 */
public final class ResourcePacks implements Listener {

    private final List<ResourcePackInfo> packs;
    private final MessageBus bus;
    private final MessageService messages;
    private final Dialogs dialogs;
    private final Map<UUID, Set<UUID>> loaded = new ConcurrentHashMap<>();

    public ResourcePacks(
            InfraConfig infra, MessageBus bus, MessageService messages, Dialogs dialogs) {
        this.packs =
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
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (packs.isEmpty()) {
            report(player, PackStatus.Status.LOADED);
            return;
        }
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
                if (done.containsAll(packIds())) {
                    report(player, PackStatus.Status.LOADED);
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

    private Set<UUID> packIds() {
        Set<UUID> ids = new HashSet<>();
        packs.forEach(p -> ids.add(p.id()));
        return ids;
    }
}
