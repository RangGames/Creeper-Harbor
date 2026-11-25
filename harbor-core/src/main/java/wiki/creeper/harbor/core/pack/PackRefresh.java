package wiki.creeper.harbor.core.pack;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.pack.PublishedPack;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * Live pack updates on servers other than the lobby (proposed). The lobby's {@link ResourcePacks}
 * sends the packs on join; when the auto-built pack changes later, players already on a main server
 * would keep the old pack until they pass through the lobby again. This resends only the auto-built
 * pack under the same pack ID, so the client swaps that one pack and keeps the others. Sent as not
 * required: declining mid-game must not disconnect anyone (the lobby still enforces it on the next
 * join).
 */
public final class PackRefresh {

    /** Same ID as {@link ResourcePacks} uses for the auto-built pack. */
    static final UUID AUTO_PACK_ID =
            UUID.nameUUIDFromBytes("harbor-pack:auto".getBytes(StandardCharsets.UTF_8));

    private final MessageService messages;

    public PackRefresh(PackManager packs, MessageService messages) {
        this.messages = messages;
        packs.onChange(this::push);
    }

    private void push(PublishedPack pack) {
        ResourcePackRequest request =
                ResourcePackRequest.resourcePackRequest()
                        .packs(
                                ResourcePackInfo.resourcePackInfo(
                                        AUTO_PACK_ID, URI.create(pack.url()), pack.sha1()))
                        .required(false)
                        .replace(false)
                        .prompt(messages.get("resourcepack.updated"))
                        .build();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendResourcePacks(request);
        }
    }
}
