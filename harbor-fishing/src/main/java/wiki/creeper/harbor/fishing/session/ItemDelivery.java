package wiki.creeper.harbor.fishing.session;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;

/**
 * Gives items created after a DB commit (trap harvests, retrievals, refunds). Main thread only.
 *
 * <p>If the inventory is full, drops it at the feet and lets only the owner pick it up for 60
 * seconds. If the player left in the meantime or the server-move snapshot was already taken,
 * putting it in the inventory would not be saved (it is already gone from the DB), so it is sent by
 * mail. If there is no mailbox, it is only logged.
 */
final class ItemDelivery {

    private final HarborApi api;
    private final FishingGear gear;
    private final Logger log;

    ItemDelivery(HarborApi api, FishingGear gear, Logger log) {
        this.api = api;
        this.gear = gear;
        this.log = log;
    }

    void give(Player player, ItemStack stack) {
        UUID uuid = player.getUniqueId();
        if (player.isOnline() && !api.players().frozen(uuid)) {
            gear.give(player, stack);
            return;
        }
        Optional<MailService> mail = api.mail();
        if (mail.isEmpty()) {
            log.error("우편함이 없어 통발 아이템을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack);
            return;
        }
        String ref = "trap_give:" + UUID.randomUUID();
        var _ =
                mail.get()
                        .sendItem(uuid, stack, "trap_give", ref)
                        .exceptionally(
                                e -> {
                                    log.error("통발 아이템 우편 실패 (관리자 복구 필요): {} {}", uuid, stack, e);
                                    return null;
                                });
    }
}
