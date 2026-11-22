package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;

/**
 * Item return (docs 20 0.1 "return on grant failure"). If online, inventory → mailbox on overflow;
 * if disconnected, mailbox. If there is no mailbox (Economy MailService), drops at the player's
 * feet, and if that also fails, logs the item info as an error so an admin can restore it. Main
 * thread only.
 */
public final class ItemReturns {

    private final HarborApi api;
    private final Logger log;

    public ItemReturns(HarborApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    /**
     * @param source mail source (e.g. aquarium_return)
     */
    public void giveBack(UUID uuid, List<ItemStack> items, String source) {
        List<ItemStack> left = new ArrayList<>();
        Player player = Bukkit.getPlayer(uuid);
        for (ItemStack item : items) {
            if (item.isEmpty()) {
                continue;
            }
            // After the server-move snapshot is taken, putting it in the inventory would not be
            // saved, so send it by mail
            if (player != null && player.isOnline() && !api.players().frozen(uuid)) {
                left.addAll(player.getInventory().addItem(item.clone()).values());
            } else {
                left.add(item.clone());
            }
        }
        if (left.isEmpty()) {
            return;
        }
        Optional<MailService> mail = api.mail();
        for (ItemStack item : left) {
            if (mail.isPresent()) {
                String ref = source + ":" + UUID.randomUUID();
                var _ =
                        mail.get()
                                .sendItem(uuid, item, source, ref)
                                .exceptionally(
                                        e -> {
                                            log.error(
                                                    "우편 반환 실패 {} {} {}",
                                                    uuid,
                                                    source,
                                                    item.getType(),
                                                    e);
                                            return null;
                                        });
            } else if (player != null && player.isOnline()) {
                player.getWorld().dropItem(Locs.of(player), item).setOwner(uuid);
            } else {
                log.error("돌려줄 곳이 없는 아이템 (우편함 없음, 오프라인): {} {} {}", uuid, source, item);
            }
        }
        if (player != null && player.isOnline() && mail.isPresent()) {
            api.messages().send(player, "collection.returned_by_mail");
        }
    }
}
