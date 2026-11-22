package wiki.creeper.harbor.quest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Delivers reward and returned items: the mailbox when the inventory is full, at the player's feet
 * without a mailbox. Main thread only.
 */
final class ItemDelivery {

    private final QuestEngine engine;

    ItemDelivery(QuestEngine engine) {
        this.engine = engine;
    }

    /**
     * Mailbox when the inventory is full (Core api MailService, implemented by Economy). Dropped at
     * the player's feet without a mailbox.
     */
    void deliverItem(Player player, ItemStack stack, String ref) {
        if (engine.api.players().frozen(player.getUniqueId())) {
            // the server-move snapshot has already been taken, so items put in the inventory would
            // not be saved
            sendMail(player.getUniqueId(), stack, ref, null);
            return;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        if (left.isEmpty()) {
            return;
        }
        for (ItemStack rest : left.values()) {
            sendMail(player.getUniqueId(), rest, ref, player.getLocation());
        }
        engine.api.messages().send(player, "quest.reward.mailed");
    }

    void sendMail(UUID uuid, ItemStack stack, String ref, @Nullable Location fallback) {
        Optional<wiki.creeper.harbor.core.api.MailService> mail = engine.api.mail();
        if (mail.isPresent()) {
            var _ = mail.get().sendItem(uuid, stack, "quest", ref);
            return;
        }
        if (fallback != null && fallback.getWorld() != null) {
            fallback.getWorld().dropItemNaturally(fallback, stack);
        } else {
            engine.log.error("우편함이 없어 퀘스트 아이템을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack);
        }
    }

    /** Returns items taken by a failed turn-in (mailbox if offline). */
    void giveBack(UUID uuid, List<ItemStack> removed) {
        Player player = Bukkit.getPlayer(uuid);
        // after the server-move snapshot is taken, items put in the inventory are not saved, so
        // mail them
        boolean frozen = player != null && engine.api.players().frozen(uuid);
        for (ItemStack stack : removed) {
            if (player != null && !frozen) {
                player.getInventory()
                        .addItem(stack)
                        .values()
                        .forEach(
                                rest -> sendMail(uuid, rest, "quest_return", player.getLocation()));
            } else {
                sendMail(uuid, stack, "quest_return", null);
            }
        }
    }
}
